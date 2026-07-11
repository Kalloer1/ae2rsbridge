@echo off
"C:\Users\Sora\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe" "D:\github\ae-rs\ae2rsbridge\gen_texture.py"
if %errorlevel% neq 0 (
    echo "Trying alternative python..."
    python "D:\github\ae-rs\ae2rsbridge\gen_texture.py"
)
pause
