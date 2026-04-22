#!/bin/bash

# Ensure required tools are installed on the system
for cmd in curl wget unzip awk sed grep tr; do
  if ! command -v $cmd &> /dev/null; then
    echo "[-] Error: Required command '$cmd' is not installed."
    exit 1
  fi
done

echo "[*] Fetching available US English models from Alpha Cephei..."

# 1. Fetch and parse the models into a bash array.
# This pipeline fetches the HTML, strips newlines, splits into table rows, 
# filters for standard US English models, formats the columns, strips the HTML tags, 
# and finally keeps only the Name, Size, and Word Error Rate (WER).
mapfile -t model_lines < <(
  curl -sL "https://alphacephei.com/vosk/models" \
    | tr -d '\n' \
    | sed -e 's/<tr/\n<tr/gi' \
    | grep -iE 'vosk-model-.*en-us.*\.zip' \
    | sed -e 's/<\/td>[[:space:]]*<td[^>]*>/ | /gi' \
    | sed -e 's/<[^>]*>//g' \
    | awk -F ' \\| ' '{print $1 " | " $2 " | " $3}' \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
)

# Guard against site layout changes or connection drops
if [ ${#model_lines[@]} -eq 0 ]; then
    echo "[-] Error: Could not fetch models. Check your internet connection or the website structure."
    exit 1
fi

echo ""
echo "Which model would you like to install?"

# 2. Display the dynamic menu
for i in "${!model_lines[@]}"; do
    echo "$((i+1))) ${model_lines[$i]}"
done

# 3. Prompt user for selection
echo -n "> "
read -r choice

# 4. Validate user input (must be an integer within the array bounds)
if ! [[ "$choice" =~ ^[0-9]+$ ]] || [ "$choice" -lt 1 ] || [ "$choice" -gt "${#model_lines[@]}" ]; then
    echo "[-] Invalid selection. Exiting."
    exit 1
fi

# 5. Extract the selected model's exact name to build the download URL
selected_line="${model_lines[$((choice-1))]}"
model_name=$(echo "$selected_line" | awk -F ' \\| ' '{print $1}' | tr -d ' ')
zip_url="https://alphacephei.com/vosk/models/${model_name}.zip"

# 6. Download the zip file
echo ""
echo "[*] Downloading ${model_name}..."
# -q hides the massive wget output, --show-progress keeps the clean progress bar
wget -q --show-progress "$zip_url" -O "${model_name}.zip"

if [ $? -ne 0 ]; then
    echo "[-] Error downloading the model. Check your connection or disk space."
    rm -f "${model_name}.zip"
    exit 1
fi

# 7. Extract the archive
echo "[*] Extracting ${model_name}.zip..."
unzip -q "${model_name}.zip"

if [ $? -ne 0 ]; then
    echo "[-] Error extracting the model. The archive may be corrupted."
    exit 1
fi

# 8. Rename the extracted folder to 'model' for the Java application
if [ -d "model" ]; then
    echo "[!] Warning: A folder named 'model' already exists. Removing it to make room for the new one..."
    rm -rf "model"
fi

if [ -d "$model_name" ]; then
    mv "$model_name" "model"
    echo "[+] Successfully extracted and mapped to ./model/"
else
    echo "[-] Extraction didn't create a directory named ${model_name}."
    echo "[-] Please check the directory contents manually and rename the target folder to 'model'."
fi

# 9. Clean up the zip file
rm -f "${model_name}.zip"
echo "[+] Cleanup complete. You are ready to run the Java backend!"