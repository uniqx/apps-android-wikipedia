package org.wikipedia.net;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AtomicFile;
import android.util.Log;
import android.webkit.WebView;

import org.wikipedia.WikipediaApp;
import org.wikipedia.util.log.L;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import info.guardianproject.netcipher.webkit.WebkitProxy;
import okhttp3.OkHttpClient;

public class ProxyHelper {

    private final Context appContext;
    private OkHttpClient okHttpClient;

    private String proxyHost = "";
    private int proxyPort = -1;


    public ProxyHelper(Context context) {
        appContext = context.getApplicationContext();

        proxyHost = "localhost";
        proxyPort = 28888;


    }

    public void configureWebViev(WebView webView) {
        // TODO: maybe ip+port should be configurable
        try {
            WebkitProxy.setProxy(WikipediaApp.class.getName(), appContext, webView, "localhost", 8118);
            L.d(String.format("ProxyHelper: configured another WebView ", webView.getId()));
        } catch (Exception e) {
            L.e(e);
        }
    }

    public OkHttpClient.Builder okHttp(OkHttpClient.Builder okHttpClientBuilder){
        // TODO: maybe ip+port should be configurable
        return okHttpClientBuilder.proxy(nonAsyncNewProxyInstanceHack(proxyHost, proxyPort));
    }

    /**
     * new Proxy() ... raises Exception because it's not allowed on main-thread.
     */
    private Proxy nonAsyncNewProxyInstanceHack(final String proxyHost, final int proxyPort){
        HandlerThread t = new HandlerThread("nonAsyncHackThread");
        t.start();
        Handler handler = new Handler(t.getLooper());

        AtomicReference<Proxy> p = new AtomicReference<>();
        handler.post(new Runnable() {
            @Override
            public void run() {
                p.set(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            }
        });

        t.quitSafely();
        try {
            t.getLooper().getThread().join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return p.get();
    }
}
