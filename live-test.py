import socket
import json
import time
import struct
import math
import argparse
import sys

try:
    import pyaudio
    from vosk import Model, KaldiRecognizer
except Exception as e:
    print(f"[-] Detailed Import Error: {e}")
    print("[-] Missing dependencies. Please run: pip install pyaudio vosk")
    sys.exit(1)

# Audio Constants
CHUNK = 512           # Reduced from 2048 to prevent UDP MTU fragmentation across the internet (1024 bytes per packet)
FORMAT = pyaudio.paInt16
CHANNELS = 1
RATE = 16000          # 16kHz required by Vosk and our Java server

def get_rms(pcm_data):
    """Calculates the Root Mean Square (volume/amplitude) of a raw PCM byte string."""
    count = len(pcm_data) // 2 # 2 bytes per int16
    if count == 0:
        return 0
    # Unpack the raw bytes into integers
    shorts = struct.unpack(f"<{count}h", pcm_data)
    sum_squares = sum(s**2 for s in shorts)
    return math.sqrt(sum_squares / count)

def calibrate_silence(stream, duration=2.0):
    """Listens to the room to find the ambient noise floor."""
    print(f"[*] Calibrating ambient noise... please stay quiet for {duration} seconds.")
    ambient_rms_list = []
    
    # Throw away the first 5 chunks to avoid mic initialization hardware "pops"
    for _ in range(5):
        stream.read(CHUNK, exception_on_overflow=False)
    
    # Calculate how many chunks we need to read for the duration
    num_chunks = int((RATE / CHUNK) * duration)
    
    for _ in range(num_chunks):
        data = stream.read(CHUNK, exception_on_overflow=False)
        ambient_rms_list.append(get_rms(data))
        
    avg_rms = sum(ambient_rms_list) / len(ambient_rms_list)
    
    # Set threshold: 2.0x ambient, with a hard minimum floor (e.g. 50.0) 
    # to prevent bugs if aggressive hardware noise cancellation causes pure digital silence
    threshold = max(avg_rms * 2.0, 50.0) 
    print(f"[+] Calibration complete. Base RMS: {avg_rms:.1f} | Silence Threshold: {threshold:.1f}")
    return threshold

def run_live_satellite(server_ip, server_port, node_id, model_path):
    # 1. Initialize PyAudio
    p = pyaudio.PyAudio()
    try:
        stream = p.open(format=FORMAT, channels=CHANNELS, rate=RATE, input=True, frames_per_buffer=CHUNK)
    except Exception as e:
        print(f"[-] Could not open microphone: {e}")
        sys.exit(1)

    # 2. Initialize Vosk (for local wake-word detection)
    print(f"[*] Loading local Vosk model from '{model_path}' for wake-word detection...")
    try:
        model = Model(model_path)
        rec = KaldiRecognizer(model, RATE)
    except Exception as e:
        print(f"[-] Failed to load Vosk model: {e}")
        sys.exit(1)

    # 3. Setup UDP Socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server_address = (server_ip, server_port)

    # 4. Calibrate the silence threshold dynamically
    silence_threshold = calibrate_silence(stream)

    print("\n[+] Satellite is LIVE. Waiting to hear 'Jarvis'...")
    
    state = "LISTENING"
    silence_duration = 0.0
    chunk_duration_sec = CHUNK / RATE
    
    # Keep a rolling buffer of the last ~1 second of audio (16000 / 512 = ~31 chunks)
    # This prevents the first word of your command from being clipped during arbitration delay
    pre_roll_chunks = int(RATE / CHUNK * 1.0) 
    audio_memory = []

    while True:
        try:
            # Read a chunk of live audio
            data = stream.read(CHUNK, exception_on_overflow=False)
            current_rms = get_rms(data)

            if state == "LISTENING":
                # Maintain the rolling memory buffer
                audio_memory.append(data)
                if len(audio_memory) > pre_roll_chunks:
                    audio_memory.pop(0)

                # Pass data to Vosk to listen for the wake word
                if rec.AcceptWaveform(data):
                    res = json.loads(rec.Result())
                    text = res.get("text", "").replace(" ", "")
                else:
                    res = json.loads(rec.PartialResult())
                    text = res.get("partial", "").replace(" ", "")

                # If the wake word is detected
                if "jarvis" in text:
                    print(f"\n[!] Wake word detected! Trigger RMS: {current_rms:.1f}")
                    
                    # Convert our raw amplitude into something looking like dB for the Java server
                    simulated_db = -20.0 if current_rms < 10 else -10.0
                    
                    trigger_payload = {"node": node_id, "amplitude": simulated_db}
                    trigger_json = json.dumps(trigger_payload)
                    
                    print(f"[*] Requesting arbitration from {server_ip}:{server_port}...")
                    sock.sendto(trigger_json.encode('utf-8'), server_address)
                    
                    # Wait for ACK with a tight 2-second timeout
                    sock.settimeout(2.0)
                    try:
                        ack_data, _ = sock.recvfrom(1024)
                        if ack_data.decode('utf-8') == "ACK_START_STREAM":
                            print("[+] Won arbitration! Streaming command...")
                            state = "STREAMING"
                            silence_duration = 0.0
                            
                            # Flush the memory buffer to the server so we don't miss the start of the command!
                            for past_chunk in audio_memory:
                                sock.sendto(past_chunk, server_address)
                            audio_memory.clear()
                            
                            # Reset Vosk so it's clean for the next wake word
                            rec.Reset()
                    except socket.timeout:
                        print("[-] Arbitration timed out. Resuming local listening.")
                        rec.Reset()
                        audio_memory.clear()
                    
                    # Set socket back to blocking for normal operations
                    sock.settimeout(None)

            elif state == "STREAMING":
                # Blast the live audio chunk to the Java server
                sock.sendto(data, server_address)

                # Track silence to determine when the user stops talking
                if current_rms < silence_threshold:
                    silence_duration += chunk_duration_sec
                else:
                    silence_duration = 0.0 # Reset timeout if they are still talking

                # 1.2 second silence timeout reached (allows for natural pauses in speech)
                if silence_duration >= 1.2:
                    print(f"[*] Detected 1.2s of silence. Stopping stream.")
                    sock.sendto(b"STREAM_EOF", server_address)
                    print("\n[+] Satellite is LIVE. Waiting to hear 'Jarvis'...")
                    state = "LISTENING"
                    rec.Reset()

        except KeyboardInterrupt:
            print("\n[*] Shutting down live satellite...")
            break

    # Cleanup
    stream.stop_stream()
    stream.close()
    p.terminate()
    sock.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Live ESP32 Satellite Emulator")
    parser.add_argument("--ip", type=str, default="127.0.0.1", help="Java server IP")
    parser.add_argument("--port", type=int, default=4900, help="Java server UDP Port")
    parser.add_argument("--node", type=str, default="live_laptop", help="Node ID")
    parser.add_argument("--model", type=str, default="model", help="Path to local Vosk model folder")

    args = parser.parse_args()
    run_live_satellite(args.ip, args.port, args.node, args.model)