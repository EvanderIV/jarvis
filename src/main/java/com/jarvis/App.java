package com.jarvis;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Scanner;

public class App {
    
    // The port your ESP32s/Python spoofers will target
    private static final int WAKE_WORD_PORT = 3900;

    public static void main(String[] args) {
        System.out.println("Initializing Arbitration Server...");

        try {
            // Initialize your controllers
            SnapcastController snapcast = new SnapcastController("127.0.0.1");
            
            // Open the central UDP socket
            DatagramSocket udpSocket = new DatagramSocket(WAKE_WORD_PORT);
            System.out.println("Listening for edge triggers on UDP port " + WAKE_WORD_PORT);

            // Pass the socket to a dedicated listener thread
            // Note: If you updated UdpListener to take the SnapcastController, pass it here!
            UdpListener triggerListener = new UdpListener(udpSocket);
            Thread listenerThread = new Thread(triggerListener);
            
            // Start listening in the background
            listenerThread.start();

            // Add a shutdown hook to close the socket cleanly if the server is killed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                udpSocket.close();
            }));

            // --- DELETED listenerThread.join() FROM HERE ---

            // Start the Live Admin Console in the foreground
            Scanner scanner = new Scanner(System.in);
            System.out.println("\n[+] Type 'help' for commands.");
            
            while (true) {
                System.out.print("Jarvis-Admin> ");
                String input = scanner.nextLine().trim();
                
                if (input.isEmpty()) continue;
                
                String[] parts = input.split(" ");
                
                if (parts[0].equals("scan")) {
                    snapcast.scanForUnregisteredSpeakers();
                } 
                else if (parts[0].equals("list")) {
                    snapcast.listRegisteredSpeakers();
                } 
                else if (parts[0].equals("register") && parts.length >= 3) {
                    String mac = parts[1];
                    String name = input.substring(input.indexOf(parts[2])); // Captures names with spaces
                    snapcast.registerSpeaker(mac, name);
                } 
                else if (parts[0].equals("remove") && parts.length == 2) {
                    snapcast.removeSpeaker(parts[1]);
                }
                else if (parts[0].equals("help")) {
                    System.out.println("Commands:");
                    System.out.println("  scan                      - Find new unregistered speakers on the network");
                    System.out.println("  list                      - Show all registered trusted speakers");
                    System.out.println("  register <mac> <alias>    - Add a speaker to the trusted list");
                    System.out.println("  remove <mac>              - Remove a speaker from the trusted list");
                    System.out.println("  exit                      - Shut down the server");
                }
                else if (parts[0].equals("exit")) {
                    System.out.println("[*] Stopping UDP Listener...");
                    triggerListener.stop();
                    scanner.close();
                    System.exit(0);
                } else {
                    System.out.println("[-] Unknown command. Type 'help' for a list of commands.");
                }
            }

        } catch (SocketException e) {
            System.err.println("Fatal Error: Could not bind to port " + WAKE_WORD_PORT);
            e.printStackTrace();
        } 
        
    }
}