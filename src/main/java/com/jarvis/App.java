package com.jarvis;

import java.net.DatagramSocket;
import java.net.SocketException;

public class App {
    
    // The port your ESP32s/Python spoofers will target
    private static final int WAKE_WORD_PORT = 3900;

    public static void main(String[] args) {
        System.out.println("Initializing Arbitration Server...");

        try {
            // Open the central UDP socket
            DatagramSocket udpSocket = new DatagramSocket(WAKE_WORD_PORT);
            System.out.println("Listening for edge triggers on UDP port " + WAKE_WORD_PORT);

            // Pass the socket to a dedicated listener thread
            UdpListener triggerListener = new UdpListener(udpSocket);
            Thread listenerThread = new Thread(triggerListener);
            
            // Start listening in the background
            listenerThread.start();

            // Add a shutdown hook to close the socket cleanly if the server is killed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                udpSocket.close();
            }));

            // Keep main thread alive
            listenerThread.join();

        } catch (SocketException e) {
            System.err.println("Fatal Error: Could not bind to port " + WAKE_WORD_PORT);
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Server thread interrupted.");
            Thread.currentThread().interrupt();
        }
    }
}