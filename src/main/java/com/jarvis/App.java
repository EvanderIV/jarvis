package com.jarvis;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Scanner;

public class App {

    public static boolean DEBUG_MODE = false;
    
    // The port your ESP32s/Python spoofers will target
    private static final int WAKE_WORD_PORT = 3900;

    public static void main(String[] args) {
        DEBUG_MODE = args.length > 0 && args[0].equalsIgnoreCase("--debug");
        System.out.println("Initializing Arbitration Server...");

        try {
            // 1. Build the Engine (Centralized Controllers)
            // Pointing to eminich.com ensures it connects to your remote Linux server 
            LmsController lmsController = new LmsController("127.0.0.1");
            MusicManager musicManager = new MusicManager(lmsController);
            CommandFulfiller fulfiller = new CommandFulfiller(musicManager, lmsController);
            
            // 2. Open the central UDP socket
            DatagramSocket udpSocket = new DatagramSocket(WAKE_WORD_PORT);
            System.out.println("Listening for edge triggers on UDP port " + WAKE_WORD_PORT);

            // 3. Pass the socket and the fulfiller to a dedicated listener thread
            UdpListener triggerListener = new UdpListener(udpSocket, fulfiller);
            Thread listenerThread = new Thread(triggerListener);
            
            // Start listening in the background
            listenerThread.start();

            // Add a shutdown hook to close the socket cleanly if the server is killed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                udpSocket.close();
            }));

            // 4. Start the Live Admin Console in the foreground
            Scanner scanner = new Scanner(System.in);
            System.out.println("\n[+] Type 'help' for commands.");
            
            while (true) {
                System.out.print("Jarvis-Admin> ");
                String input = scanner.nextLine().trim();
                
                if (input.isEmpty()) continue;
                
                String[] parts = input.split(" ");
                
                // Using the centralized lmsController for all admin commands
                if (parts[0].equals("scan")) {
                    lmsController.scanForUnregisteredSpeakers();
                } 
                else if (parts[0].equals("list")) {
                    lmsController.listRegisteredSpeakers();
                } 
                else if (parts[0].equals("register") && parts.length >= 3) {
                    String mac = parts[1];
                    String name = input.substring(input.indexOf(parts[2])); // Captures names with spaces
                    lmsController.registerSpeaker(mac, name);
                } 
                else if (parts[0].equals("remove") && parts.length == 2) {
                    lmsController.removeSpeaker(parts[1]);
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