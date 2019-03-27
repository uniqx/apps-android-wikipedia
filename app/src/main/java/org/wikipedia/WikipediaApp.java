package org.wikipedia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatDelegate;
import android.text.TextUtils;
import android.util.Log;
import android.view.Window;
import android.webkit.WebView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.leakcanary.RefWatcher;

import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ProxyToServerConnection;
import org.wikipedia.analytics.FunnelManager;
import org.wikipedia.analytics.SessionFunnel;
import org.wikipedia.auth.AccountUtil;
import org.wikipedia.concurrency.RxBus;
import org.wikipedia.connectivity.NetworkConnectivityReceiver;
import org.wikipedia.crash.CrashReporter;
import org.wikipedia.crash.hockeyapp.HockeyAppCrashReporter;
import org.wikipedia.database.Database;
import org.wikipedia.database.DatabaseClient;
import org.wikipedia.dataclient.ServiceFactory;
import org.wikipedia.dataclient.SharedPreferenceCookieManager;
import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.fresco.DisabledCache;
import org.wikipedia.dataclient.okhttp.CacheableOkHttpNetworkFetcher;
import org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory;
import org.wikipedia.edit.summaries.EditSummary;
import org.wikipedia.events.ChangeTextSizeEvent;
import org.wikipedia.events.ThemeChangeEvent;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.language.AcceptLanguageUtil;
import org.wikipedia.language.AppLanguageState;
import org.wikipedia.net.ProxyHelper;
import org.wikipedia.notifications.NotificationPollBroadcastReceiver;
import org.wikipedia.page.tabs.Tab;
import org.wikipedia.pageimages.PageImage;
import org.wikipedia.search.RecentSearch;
import org.wikipedia.settings.Prefs;
import org.wikipedia.settings.RemoteConfig;
import org.wikipedia.settings.SiteInfoClient;
import org.wikipedia.theme.Theme;
import org.wikipedia.util.DimenUtil;
import org.wikipedia.util.ReleaseUtil;
import org.wikipedia.util.log.L;
import org.wikipedia.views.ViewAnimations;
import org.wikipedia.zero.WikipediaZeroHandler;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import info.guardianproject.tfd.TcpForwardDaemon;
import info.pluggabletransports.dispatch.Connection;
import info.pluggabletransports.dispatch.DispatchConstants;
import info.pluggabletransports.dispatch.Dispatcher;
import info.pluggabletransports.dispatch.transports.legacy.Obfs4Transport;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.internal.functions.Functions;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.wikipedia.settings.Prefs.getTextSizeMultiplier;
import static org.wikipedia.util.DimenUtil.getFontSizeFromSp;
import static org.wikipedia.util.ReleaseUtil.getChannel;

public class WikipediaApp extends Application {
    private final RemoteConfig remoteConfig = new RemoteConfig();
    private final Map<Class<?>, DatabaseClient<?>> databaseClients = Collections.synchronizedMap(new HashMap<>());
    private Handler mainThreadHandler;
    private AppLanguageState appLanguageState;
    private FunnelManager funnelManager;
    private SessionFunnel sessionFunnel;
    private NetworkConnectivityReceiver connectivityReceiver = new NetworkConnectivityReceiver();
    private ActivityLifecycleHandler activityLifecycleHandler = new ActivityLifecycleHandler();
    private Database database;
    private String userAgent;
    private WikiSite wiki;
    private CrashReporter crashReporter;
    private RefWatcher refWatcher;
    private RxBus bus;
    private Theme currentTheme = Theme.getFallback();
    private List<Tab> tabList = new ArrayList<>();

    private WikipediaZeroHandler zeroHandler;

    private static WikipediaApp INSTANCE;

    public WikipediaApp() {
        INSTANCE = this;
    }

    public static WikipediaApp getInstance() {
        return INSTANCE;
    }

    public SessionFunnel getSessionFunnel() {
        return sessionFunnel;
    }

    public RefWatcher getRefWatcher() {
        return refWatcher;
    }

    public RxBus getBus() {
        return bus;
    }

    public Database getDatabase() {
        return database;
    }

    public FunnelManager getFunnelManager() {
        return funnelManager;
    }

    public WikipediaZeroHandler getWikipediaZeroHandler() {
        return zeroHandler;
    }

    public RemoteConfig getRemoteConfig() {
        return remoteConfig;
    }

    /**
     * Gets the currently-selected theme for the app.
     * @return Theme that is currently selected, which is the actual theme ID that can
     * be passed to setTheme() when creating an activity.
     */
    @NonNull
    public Theme getCurrentTheme() {
        return currentTheme;
    }

    @NonNull public AppLanguageState language() {
        return appLanguageState;
    }

    @NonNull
    public String getAppOrSystemLanguageCode() {
        String code = appLanguageState.getAppLanguageCode();
        if (AccountUtil.getUserIdForLanguage(code) == 0) {
            getUserIdForLanguage(code);
        }
        return code;
    }

    static class AptConfig {
        //final HttpToSocksProxyConfig httpProxyConfig;
        //final TcpToSocksForwardDaemonConfig forwardDaemonConfig;
        final PtBridgeConfig ptBridgeConfig;
        final PtClientConfig ptClientConfig;

        AptConfig(/*HttpToSocksProxyConfig httpProxyConfig, TcpToSocksForwardDaemonConfig forwardDaemonConfig,*/ PtClientConfig ptClientConfig, PtBridgeConfig ptBridgeConfig) {
            //this.httpProxyConfig = httpProxyConfig;
            //this.forwardDaemonConfig = forwardDaemonConfig;
            this.ptBridgeConfig = ptBridgeConfig;
            this.ptClientConfig = ptClientConfig;
        }
    }

    static class HttpToSocksProxyConfig extends ProxyToServerConnection.Socks5Settings {
        HttpToSocksProxyConfig(String host, int port){
            super(host, port);
        }
    };

    static class TcpToSocksForwardDaemonConfig extends ProxyToServerConnection.Socks5Settings {
        TcpToSocksForwardDaemonConfig(String host, int port) {
            super(host, port);
        }
    }

    static class PtBridgeConfig extends ProxyToServerConnection.Socks5Settings {
        PtBridgeConfig(String host, int port, String user, String pass) {
            super(host, port, user, pass);
        }
    }

    static class PtClientConfig extends ProxyToServerConnection.Socks5Settings {
        PtClientConfig(String host, int port) {
            super(host, port);
        }
    }

    public static void initTcpForwardDaemon(TcpToSocksForwardDaemonConfig forwardDaemonConfig, PtClientConfig ptClientConfig, PtBridgeConfig ptBridgeConfig) {

        TcpForwardDaemon forwardDaemon = new TcpForwardDaemon(
                forwardDaemonConfig.socks5Ip,
                forwardDaemonConfig.socks5Port,
                ptBridgeConfig.socks5Ip,
                ptBridgeConfig.socks5Port,
                ptClientConfig.socks5Ip,
                ptClientConfig.socks5Port,
                ptBridgeConfig.socks5User,
                ptBridgeConfig.socks5Pass);

        forwardDaemon.setErrorListener(error -> Log.e("#pt", "tcp forward daemon encountered an error", error));

        new Thread(){
            @Override
            public void run() {
                forwardDaemon.run();
            }
        }.start();

        Log.i("#pt", "initialized tcp forward daemon: " + forwardDaemonConfig.socks5Ip + ":" + forwardDaemonConfig.socks5Port);
    }

    private int initUpstreamProxy (final int localHttpPort, int ptSocksPort, String username, String password) {
        try {
            final StringBuffer socksProxy = new StringBuffer();
            socksProxy.append("socks5://");
            socksProxy.append(URLEncoder.encode(username, "UTF-8"));
            socksProxy.append(":");
            socksProxy.append(URLEncoder.encode(password, "UTF-8"));
            socksProxy.append('@');
            socksProxy.append("127.0.0.1:");
            socksProxy.append(ptSocksPort);

            Log.d("Proxy", "proxy" + socksProxy.toString());

            new Thread() {
                public void run() {
                    // TODO;
                    // Helloproxy.startProxy(":" + localHttpPort, socksProxy.toString());
                }
            }.start();

            return localHttpPort;
        } catch (UnsupportedEncodingException e) {
            Log.e("#pt", "", e);
        }
        return -1;
    }

    public static String parseBridgelineArgs(String bridgeline) {
        StringBuilder ret = new StringBuilder();
        String[] tokens = bridgeline.split(" ");
        for (int i=3; i<tokens.length; i++) {
            if (i != 3) {
                ret.append(";");
            }
            ret.append(tokens[i]);
        }
        return ret.toString();
    }

    public static void initLittleProxy(HttpToSocksProxyConfig httpProxyConfig, TcpToSocksForwardDaemonConfig forwardDaemonConfig){
        ProxyToServerConnection.socks5Settings = forwardDaemonConfig;
        HttpProxyServer server =
                DefaultHttpProxyServer.bootstrap()
                        .withAddress(new InetSocketAddress(httpProxyConfig.socks5Ip, httpProxyConfig.socks5Port))
                        .start();
        Log.i("#pt", "initialized littleproxy: " + httpProxyConfig.socks5Ip + ":" + httpProxyConfig.socks5Port);
    }

    public static AptConfig initApt(){

        PtClientConfig ptClientConfig = null;
        PtBridgeConfig ptBridgeConfig = null;

        String bridgeline = "obfs4 37.218.247.26:443 no-fingerpring cert=72cefPoNMgI5qFhHTWGvs+LV4jIroE4i/0RyJRLOCGTe9rZTOy5vT2I1QnNEuWkK044SQg iat-mode=0";
        new Obfs4Transport().register();
        Properties options = new Properties();
        Obfs4Transport.setPropertiesFromBridgeString(options, bridgeline);
        Obfs4Transport transport = (Obfs4Transport) Dispatcher.get().getTransport(INSTANCE, DispatchConstants.PT_TRANSPORTS_OBFS4, options);
        Connection connection = transport.connect(options.getProperty("address"));
        if (connection != null) {
            ptClientConfig = new PtClientConfig(connection.getLocalAddress().getHostAddress(), connection.getLocalPort());
            ptBridgeConfig = new PtBridgeConfig(connection.getRemoteAddress().getHostAddress(), connection.getRemotePort(), connection.getProxyUsername(), connection.getProxyPassword());
        }
        return new AptConfig(ptClientConfig, ptBridgeConfig);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // APT
        HttpToSocksProxyConfig littleProxyConfig = new HttpToSocksProxyConfig("127.0.0.1", 8088);
        ProxyHelper.setHttpProxy(littleProxyConfig.socks5Ip, littleProxyConfig.socks5Port);
        new Thread(){
            @Override
            public void run() {
                AptConfig config = initApt();

                TcpToSocksForwardDaemonConfig forwardDaemonConfig = new TcpToSocksForwardDaemonConfig("127.0.0.1", 9099);
                initTcpForwardDaemon(forwardDaemonConfig, config.ptClientConfig, config.ptBridgeConfig);

                initLittleProxy(littleProxyConfig, forwardDaemonConfig);
            }
        }.start();

        WikiSite.setDefaultBaseUrl(Prefs.getMediaWikiBaseUrl());

        // Register here rather than in AndroidManifest.xml so that we can target Android N.
        // https://developer.android.com/topic/performance/background-optimization.html#connectivity-action
        registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        zeroHandler = new WikipediaZeroHandler(this);

        // HockeyApp exception handling interferes with the test runner, so enable it only for
        // beta and stable releases
        if (!ReleaseUtil.isPreBetaRelease()) {
            initExceptionHandling();
        }

        refWatcher = Prefs.isMemoryLeakTestEnabled() ? LeakCanary.install(this) : RefWatcher.DISABLED;

        // See Javadocs and http://developer.android.com/tools/support-library/index.html#rev23-4-0
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

        // This handler will catch exceptions thrown from Observables after they are disposed,
        // or from Observables that are (deliberately or not) missing an onError handler.
        // TODO: consider more comprehensive handling of these errors.
        RxJavaPlugins.setErrorHandler(Functions.emptyConsumer());

        bus = new RxBus();

        ViewAnimations.init(getResources());
        currentTheme = unmarshalCurrentTheme();

        appLanguageState = new AppLanguageState(this);
        funnelManager = new FunnelManager(this);
        sessionFunnel = new SessionFunnel(this);
        database = new Database(this);

        initTabs();

        enableWebViewDebugging();

        ImagePipelineConfig config = ImagePipelineConfig.newBuilder(this)
                .setNetworkFetcher(new CacheableOkHttpNetworkFetcher(OkHttpConnectionFactory.getClient()))
                .setFileCacheFactory(DisabledCache.factory())
                .build();
        try {
            Fresco.initialize(this, config);
        } catch (Exception e) {
            L.e(e);
            // TODO: Remove when we're able to initialize Fresco in test builds.
        }

        registerActivityLifecycleCallbacks(activityLifecycleHandler);

        // Kick the notification receiver, in case it hasn't yet been started by the system.
        NotificationPollBroadcastReceiver.startPollTask(this);
    }

    public int getVersionCode() {
        // Our ABI-specific version codes are structured in increments of 10000, so just
        // take the actual version code modulo the increment.
        final int versionCodeMod = 10000;
        return BuildConfig.VERSION_CODE % versionCodeMod;
    }

    public String getUserAgent() {
        if (userAgent == null) {
            String channel = getChannel(this);
            channel = channel.equals("") ? channel : " ".concat(channel);
            userAgent = String.format("WikipediaApp/%s (Android %s; %s)%s",
                    BuildConfig.VERSION_NAME,
                    Build.VERSION.RELEASE,
                    getString(R.string.device_type),
                    channel
            );
        }
        return userAgent;
    }

    /**
     * @return the value that should go in the Accept-Language header.
     */
    @NonNull
    public String getAcceptLanguage(@Nullable WikiSite wiki) {
        String wikiLang = wiki == null || "meta".equals(wiki.languageCode())
                ? ""
                : defaultString(wiki.languageCode());
        return AcceptLanguageUtil.getAcceptLanguage(wikiLang, appLanguageState.getAppLanguageCode(),
                appLanguageState.getSystemLanguageCode());
    }

    /**
     * Default wiki for the app
     * You should use PageTitle.getWikiSite() to get the article wiki
     */
    @NonNull public synchronized WikiSite getWikiSite() {
        // TODO: why don't we ensure that the app language hasn't changed here instead of the client?
        if (wiki == null) {
            String lang = Prefs.getMediaWikiBaseUriSupportsLangCode() ? getAppOrSystemLanguageCode() : "";
            WikiSite newWiki = WikiSite.forLanguageCode(lang);
            // Kick off a task to retrieve the site info for the current wiki
            SiteInfoClient.updateFor(newWiki);
            wiki = newWiki;
            return newWiki;
        }
        return wiki;
    }

    public <T> DatabaseClient<T> getDatabaseClient(Class<T> cls) {
        if (!databaseClients.containsKey(cls)) {
            DatabaseClient<?> client;
            if (cls.equals(HistoryEntry.class)) {
                client = new DatabaseClient<>(this, HistoryEntry.DATABASE_TABLE);
            } else if (cls.equals(PageImage.class)) {
                client = new DatabaseClient<>(this, PageImage.DATABASE_TABLE);
            } else if (cls.equals(RecentSearch.class)) {
                client = new DatabaseClient<>(this, RecentSearch.DATABASE_TABLE);
            } else if (cls.equals(EditSummary.class)) {
                client = new DatabaseClient<>(this, EditSummary.DATABASE_TABLE);
            } else {
                throw new RuntimeException("No persister found for class " + cls.getCanonicalName());
            }
            databaseClients.put(cls, client);
        }
        //noinspection unchecked
        return (DatabaseClient<T>) databaseClients.get(cls);
    }

    /**
     * Get this app's unique install ID, which is a UUID that should be unique for each install
     * of the app. Useful for anonymous analytics.
     * @return Unique install ID for this app.
     */
    public String getAppInstallID() {
        String id = Prefs.getAppInstallId();
        if (id == null) {
            id = UUID.randomUUID().toString();
            Prefs.setAppInstallId(id);
        }
        return id;
    }

    /**
     * Sets the theme of the app. If the new theme is the same as the current theme, nothing happens.
     * Otherwise, an event is sent to notify of the theme change.
     */
    public void setCurrentTheme(@NonNull Theme theme) {
        if (theme != currentTheme) {
            currentTheme = theme;
            Prefs.setThemeId(currentTheme.getMarshallingId());
            bus.post(new ThemeChangeEvent());
        }
    }

    public boolean setFontSizeMultiplier(int multiplier) {
        int minMultiplier = getResources().getInteger(R.integer.minTextSizeMultiplier);
        int maxMultiplier = getResources().getInteger(R.integer.maxTextSizeMultiplier);
        if (multiplier < minMultiplier) {
            multiplier = minMultiplier;
        } else if (multiplier > maxMultiplier) {
            multiplier = maxMultiplier;
        }
        if (multiplier != getTextSizeMultiplier()) {
            Prefs.setTextSizeMultiplier(multiplier);
            bus.post(new ChangeTextSizeEvent());
            return true;
        }
        return false;
    }

    public void putCrashReportProperty(String key, String value) {
        if (!ReleaseUtil.isPreBetaRelease()) {
            crashReporter.putReportProperty(key, value);
        }
    }

    public void checkCrashes(@NonNull Activity activity) {
        if (!ReleaseUtil.isPreBetaRelease()) {
            crashReporter.checkCrashes(activity);
        }
    }

    public Handler getMainThreadHandler() {
        if (mainThreadHandler == null) {
            mainThreadHandler = new Handler(getMainLooper());
        }
        return mainThreadHandler;
    }

    public List<Tab> getTabList() {
        return tabList;
    }

    public void commitTabState() {
        if (tabList.isEmpty()) {
            Prefs.clearTabs();
            initTabs();
        } else {
            Prefs.setTabs(tabList);
        }
    }

    public int getTabCount() {
        // handle the case where we have a single tab with an empty backstack,
        // which shouldn't count as a valid tab:
        return tabList.size() > 1 ? tabList.size()
                : tabList.isEmpty() ? 0 : tabList.get(0).getBackStack().isEmpty() ? 0 : tabList.size();
    }

    public boolean isOnline() {
        return connectivityReceiver.isOnline();
    }

    /**
     * Gets the current size of the app's font. This is given as a device-specific size (not "sp"),
     * and can be passed directly to setTextSize() functions.
     * @param window The window on which the font will be displayed.
     * @return Actual current size of the font.
     */
    public float getFontSize(Window window) {
        return getFontSizeFromSp(window,
                getResources().getDimension(R.dimen.textSize)) * (1.0f + getTextSizeMultiplier()
                * DimenUtil.getFloat(R.dimen.textSizeMultiplierFactor));
    }

    public synchronized void resetWikiSite() {
        wiki = null;
    }

    public void logOut() {
        L.v("logging out");
        AccountUtil.removeAccount();
        SharedPreferenceCookieManager.getInstance().clearAllCookies();
    }

    private void initExceptionHandling() {
        crashReporter = new HockeyAppCrashReporter(getString(R.string.hockeyapp_app_id), consentAccessor());
        crashReporter.registerCrashHandler(this);

        L.setRemoteLogger(crashReporter);
    }

    private CrashReporter.AutoUploadConsentAccessor consentAccessor() {
        return Prefs::isCrashReportAutoUploadEnabled;
    }

    private void enableWebViewDebugging() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private Theme unmarshalCurrentTheme() {
        int id = Prefs.getThemeId();
        Theme result = Theme.ofMarshallingId(id);
        if (result == null) {
            L.d("Theme id=" + id + " is invalid, using fallback.");
            result = Theme.getFallback();
        }
        return result;
    }

    @SuppressLint("CheckResult")
    private void getUserIdForLanguage(@NonNull final String code) {
        if (!AccountUtil.isLoggedIn() || TextUtils.isEmpty(AccountUtil.getUserName())) {
            return;
        }
        final WikiSite wikiSite = WikiSite.forLanguageCode(code);
        ServiceFactory.get(wikiSite).getUserInfo(AccountUtil.getUserName())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> {
                    if (AccountUtil.isLoggedIn() && response.query().getUserResponse(AccountUtil.getUserName()) != null) {
                        // noinspection ConstantConditions
                        int id = response.query().userInfo().id();
                        AccountUtil.putUserIdForLanguage(code, id);
                        L.d("Found user ID " + id + " for " + code);
                    }
                }, caught -> L.e("Failed to get user ID for " + code, caught));
    }

    private void initTabs() {
        if (Prefs.hasTabs()) {
            tabList.addAll(Prefs.getTabs());
        }

        if (tabList.isEmpty()) {
            tabList.add(new Tab());
        }
    }

    public boolean haveMainActivity() {
        return activityLifecycleHandler.haveMainActivity();
    }
}
