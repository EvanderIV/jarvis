import os
import sys
import subprocess
import argparse

def check_ffmpeg():
    """Check if ffmpeg is installed and available in the system PATH."""
    try:
        subprocess.run(["ffmpeg", "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except FileNotFoundError:
        return False

def convert_audio(input_file, output_file):
    if not os.path.exists(input_file):
        print(f"[-] Error: Input file '{input_file}' not found.")
        sys.exit(1)

    if not output_file:
        # Auto-generate an output filename if one wasn't provided
        base_name = os.path.basename(input_file)
        dir_name = os.path.dirname(input_file)
        output_file = os.path.join(dir_name, f"16k_{base_name}")

    print(f"[*] Converting '{input_file}' -> '{output_file}'...")
    print("[*] Target format: 16000 Hz, 16-bit, Mono")

    # The exact ffmpeg command arguments to format for Vosk/ESP32
    # -y              : overwrite output files without asking
    # -i              : input file
    # -ar 16000       : set audio sampling rate to 16000 Hz
    # -ac 1           : set number of audio channels to 1 (mono)
    # -sample_fmt s16 : set sample format to 16-bit PCM
    command = [
        "ffmpeg", "-y", "-i", input_file,
        "-ar", "16000", "-ac", "1", "-sample_fmt", "s16",
        output_file
    ]

    try:
        # Run ffmpeg silently unless there is an error
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        
        if result.returncode == 0:
            print(f"[+] Success! Saved to '{output_file}'")
        else:
            print(f"[-] ffmpeg encountered an error:\n{result.stderr}")
    except Exception as e:
        print(f"[-] An unexpected error occurred: {e}")

if __name__ == "__main__":
    if not check_ffmpeg():
        print("[-] Error: 'ffmpeg' is not installed or not in your system's PATH.")
        print("[-] Windows: Download it from gyan.dev or use 'winget install ffmpeg'")
        print("[-] Linux: Run 'sudo apt install ffmpeg'")
        sys.exit(1)

    parser = argparse.ArgumentParser(description="Convert audio to 16kHz, 16-bit, Mono for Vosk/ESP32.")
    parser.add_argument("input", help="Path to the input audio file")
    parser.add_argument("-o", "--output", help="Path to the output .wav file (optional)", default=None)

    args = parser.parse_args()
    convert_audio(args.input, args.output)