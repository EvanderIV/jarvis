import socket
import json
import wave
import time
import argparse
import sys

def emulate_satellite(server_ip, server_port, wav_path, node_id, amplitude):
    # 1. Setup UDP Socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server_address = (server_ip, server_port)
    
    # 2. Construct and send the Wake-Word Trigger
    trigger_payload = {
        "node": node_id,
        "amplitude": amplitude
    }
    trigger_json = json.dumps(trigger_payload)
    
    print(f"[*] Firing wake-word trigger to {server_ip}:{server_port}")
    print(f"[*] Payload: {trigger_json}")
    sock.sendto(trigger_json.encode('utf-8'), server_address)
    
    # 3. Wait for Arbitration ACK from Java server
    sock.settimeout(5.0) # Wait up to 5 seconds to win arbitration
    print("[*] Waiting for arbitration ACK from Java backend...")
    
    try:
        data, _ = sock.recvfrom(1024)
        response = data.decode('utf-8').strip()
        
        if response == "ACK_START_STREAM":
            print("[+] Won arbitration! Server sent ACK_START_STREAM.")
        else:
            print(f"[-] Received unknown response: {response}")
            print("[-] Aborting stream.")
            sys.exit(1)
            
    except socket.timeout:
        print("[-] Arbitration timed out. Did not receive ACK. (Did another node win, or is the server down?)")
        sys.exit(1)

    # 4. Stream Raw Audio (Emulating ESP32 I2S Buffer)
    print(f"[*] Opening audio file: {wav_path}")
    try:
        wf = wave.open(wav_path, 'rb')
    except wave.Error as e:
        print(f"[-] Error opening WAV file: {e}")
        sys.exit(1)

    # Extract audio specs
    sample_rate = wf.getframerate()
    sample_width = wf.getsampwidth() # 2 bytes for 16-bit audio
    channels = wf.getnchannels()     # 1 for mono
    
    print(f"[*] Audio format: {sample_rate}Hz, {sample_width*8}-bit, Channels: {channels}")
    
    # We want to send 1024 bytes per packet to avoid UDP fragmentation (MTU is usually 1500)
    # 1024 bytes / (2 bytes per sample) = 512 frames per chunk
    frames_per_chunk = 1024 // (sample_width * channels)
    
    # Calculate exactly how long this chunk of audio lasts in real-time
    chunk_duration_seconds = frames_per_chunk / sample_rate

    print("[*] Streaming raw PCM firehose...")
    start_time = time.time()
    packets_sent = 0

    while True:
        # Read raw binary PCM frames (this strips the WAV header entirely)
        raw_pcm_bytes = wf.readframes(frames_per_chunk)
        
        if not raw_pcm_bytes:
            break # End of audio file
            
        # Blast the raw bytes over UDP
        sock.sendto(raw_pcm_bytes, server_address)
        packets_sent += 1
        
        # ESP32s generate audio in real-time, so we must sleep to emulate real-time flow.
        # If we just blast the file instantly, the Java server's buffer might choke.
        time.sleep(chunk_duration_seconds)

    duration = time.time() - start_time
    print(f"[+] Stream complete! Sent {packets_sent} UDP packets in {duration:.2f} seconds.")
    
    # 5. Send an EOF signal so Java knows to process the speech-to-text
    # (Using a distinct string that won't be confused with random audio bytes)
    eof_signal = "STREAM_EOF"
    sock.sendto(eof_signal.encode('utf-8'), server_address)
    print("[*] Sent STREAM_EOF signal. Emulator shutting down.")
    
    sock.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ESP32 Audio Satellite Emulator")
    parser.add_argument("--ip", type=str, default="127.0.0.1", help="Java server IP")
    parser.add_argument("--port", type=int, default=3900, help="Java server UDP Port")
    parser.add_argument("--wav", type=str, required=True, help="Path to .wav file to stream")
    parser.add_argument("--node", type=str, default="test_laptop", help="Node ID (e.g., living_room)")
    parser.add_argument("--amp", type=float, default=-12.5, help="Simulated wake-word amplitude")

    args = parser.parse_args()
    emulate_satellite(args.ip, args.port, args.wav, args.node, args.amp)