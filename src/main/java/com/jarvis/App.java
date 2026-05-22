package com.jarvis;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Scanner;
import java.util.Arrays;

public class App {

    public static boolean DEBUG_MODE = false;

    // The port your ESP32s/Python spoofers will target
    private static final int WAKE_WORD_PORT = 3900;
    public static String WAKE_WORD = "jarvis";

    public static void main(String[] args) {
        DEBUG_MODE = args.length > 0 && args[0].equalsIgnoreCase("--debug");
        System.out.println("Initializing Arbitration Server...");

        try {
            // 1. Build the Engine (Centralized Controllers)
            // Pointing to eminich.com ensures it connects to your remote Linux server
            LmsController lmsController = new LmsController("127.0.0.1");
            MusicManager musicManager = new MusicManager(lmsController);
            CommandFulfiller fulfiller = new CommandFulfiller(musicManager, lmsController);

            // 1.5. Initialize the Routine Engine and Schedule the Wake-Up Routine!
            RoutineEngine routineEngine = new RoutineEngine(lmsController, musicManager);
            
            routineEngine.createRoutine(lmsController.getAllRegisteredSpeakers())
                .triggerAtTime(8, 0)
                .setVolumeRatio(0.0)
                .playTheme("+Wakeup -Somber", 0, 3)
                .fadeVolumeRatio(0.0, 0.33, 30)
                .playTheme("+Relaxing Upbeat Happy -Epic -Somber -Relaxing -Meme -Profanity -Wakeup", 2, 2)
                .fadeVolumeRatio(0.33, 1.0, 150)
                .waitMinutes(2.5)
                .playTheme("+Upbeat Relaxing Happy -Epic -Somber -Relaxing -Meme -Profanity -Wakeup", 2, 2)
                .waitMinutes(6)
                .fadeVolumeRatio(1.0, 0.7, 120)
                .playTheme("+Upbeat Relaxing Happy -Epic -Somber -Relaxing -Meme -Profanity -Wakeup", 2, 2)
                .build();

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
                if (!scanner.hasNextLine()) {
                    // stdin is closed (running as a service with no terminal) — idle quietly
                    try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    continue;
                }
                System.out.print("Jarvis-Admin> ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty())
                    continue;

                String[] parts = input.split(" ");

                // Using the centralized lmsController for all admin commands
                if (parts[0].equals("scan")) {
                    lmsController.scanForUnregisteredSpeakers();
                } else if (parts[0].equals("list")) {
                    lmsController.listRegisteredSpeakers();
                } else if (parts[0].equals("register") && parts.length >= 3) {
                    String mac = parts[1];
                    String name = input.substring(input.indexOf(parts[2])); // Captures names with spaces
                    lmsController.registerSpeaker(mac, name);
                } else if (parts[0].equals("remove") && parts.length == 2) {
                    lmsController.removeSpeaker(parts[1]);
                } else if (parts[0].equals("volume") && parts.length >= 3) {
                    try {
                        int vol = Integer.parseInt(parts[parts.length - 1]);
                        // Safely extract the alias (even if it has spaces) by joining everything in the middle
                        String target = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length - 1));
                        lmsController.setDefaultVolume(target, vol);
                    } catch (NumberFormatException e) {
                        System.out.println("[-] Invalid volume. Usage: volume <mac or alias> <level>");
                    }
                } else if (parts[0].equals("help")) {
                    System.out.println("Commands:");
                    System.out.println("  scan                      - Find new unregistered speakers on the network");
                    System.out.println("  list                      - Show all registered trusted speakers");
                    System.out.println("  register <mac> <alias>    - Add a speaker to the trusted list");
                    System.out.println("  remove <mac>              - Remove a speaker from the trusted list");
                    System.out.println("  volume <target> <level>   - Set and save default volume for an alias or MAC (e.g., volume Bedroom 60)");
                    System.out.println("  exit                      - Shut down the server");
                } else if (parts[0].equals("exit")) {
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