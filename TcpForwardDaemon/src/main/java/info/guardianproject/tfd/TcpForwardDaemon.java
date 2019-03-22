package info.guardianproject.tfd;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import com.runjva.sourceforge.jsocks.protocol.SocksSocket;
import com.runjva.sourceforge.jsocks.protocol.UserPasswordAuthentication;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
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
            e.printStackTrace();
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
            try {
                Socket serverSocket;
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

            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

    public static void main(String[] args) {
        new TcpForwardDaemon("127.0.0.1", 9099, "127.0.0.1", 9050).run();
    }
}
