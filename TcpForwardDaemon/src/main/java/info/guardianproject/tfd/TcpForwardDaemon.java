package info.guardianproject.tfd;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import com.runjva.sourceforge.jsocks.protocol.SocksSocket;
import com.runjva.sourceforge.jsocks.protocol.UserPasswordAuthentication;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;


public class TcpForwardDaemon {

    private final String listeningHost;
    private final int listeningPort;
    private final String proxyHost;
    private final int proxyPort;
    private final String proxyUser;
    private final String proxyPass;
    private final String targetHost;
    private final int targetPort;

    private volatile boolean running = false;

    public TcpForwardDaemon(String listeningHost, int listeningPort, String targetHost, int targetPort) {
        this(listeningHost, listeningPort, targetHost, targetPort, null, -1, null, null);
    }

    public TcpForwardDaemon(String listeningHost, int listeningPort, String targetHost, int targetPort, String proxyHost, int proxyPort, String proxyUser, String proxyPass) {
        this.listeningHost = listeningHost;
        this.listeningPort = listeningPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.proxyUser = proxyUser;
        this.proxyPass = proxyPass;
    }

    public void run() {
        try {
            ServerSocket serverSocket = new ServerSocket(listeningPort, 50, InetAddress.getByName(listeningHost));

            running = true;
            while (running) {
                Socket s = serverSocket.accept();
                new TunnelThread(s).start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        running = false;
    }

    private class TunnelThread extends Thread{

        private final Socket clientSocket;

        public TunnelThread(Socket socket) {
            this.clientSocket = socket;

        }

        @Override
        public void run() {
            Socket serverSocket = null;
            try {
                if (proxyHost != null && proxyPort > 0) {
                    if (proxyUser != null && proxyPass != null) {
                        Socks5Proxy proxy = new Socks5Proxy(proxyHost, proxyPort);
                        UserPasswordAuthentication auth = new UserPasswordAuthentication(proxyUser,proxyPass);
                        proxy.setAuthenticationMethod(0,null);
                        proxy.setAuthenticationMethod(UserPasswordAuthentication.METHOD_ID, auth);
                        serverSocket = new SocksSocket(proxy, targetHost, targetPort);
                    } else {
                        serverSocket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort)));
                        serverSocket.connect(new InetSocketAddress(targetHost, targetPort));
                    }
                } else {
                    serverSocket = new Socket(targetHost, targetPort);
                }
                InputStream serverIn = serverSocket.getInputStream();
                OutputStream serverOut = serverSocket.getOutputStream();

                InputStream clientIn = clientSocket.getInputStream();
                OutputStream clientOut = clientSocket.getOutputStream();

                byte[] buf = new byte[1024];
                while (serverIn.available() >= 0 || clientIn.available() >= 0) {
                    if (serverIn.available() > 0) {
                        int len = serverIn.read(buf);
                        clientOut.write(buf, 0, len);
                    }
                    if (clientIn.available() > 0) {
                        int len = clientIn.read(buf);
                        serverOut.write(buf, 0, len);
                    } else {
                        Thread.sleep(1);
                    }
                }

                serverSocket.close();
                clientSocket.close();

            } catch (Exception e) {
                if (errorListener != null) {
                    errorListener.error(e);
                }
            } finally {
                safeClose(serverSocket);
                safeClose(clientSocket);
            }

        }
    }

    private void safeClose(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
            }
        }
    }

    private ErrorListener errorListener = null;

    public interface ErrorListener{
        void error(Throwable error);
    }

    public void setErrorListener(ErrorListener errorListener) {
        this.errorListener = errorListener;
    }

    /**
     * for quick and dirty testing whether if this thing really works.
     */
    public static void main(String[] args) {
        // forward port 9099 to tor socks proxy 9050,
        //new TcpForwardDaemon("127.0.0.1", 9099, "127.0.0.1", 9050).run();

        // forward over remote obfs4 socks proxy:
        new TcpForwardDaemon("127.0.0.1", 9099, "37.218.247.26", 443, "127.0.0.1", 43297, "cert=72cefPoNMgI5qFhHTWGvs+LV4jIroE4i/0RyJRLOCGTe9rZTOy5vT2I1QnNEuWkK044SQg;iat-mode=0", "\u0000").run();


        // client useage example: curl --socks5-host 127.0.0.1:9099 guardianproject.info
    }
}
