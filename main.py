from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
import uuid
import asyncio
import subprocess
from pathlib import Path
from typing import Dict, Set
import json

app = FastAPI()

# Serve video segments statically
app.mount("/videos", StaticFiles(directory="/path/to/video_storage"), name="videos")

# Lobby state
lobbies: Dict[str, dict] = {}
lobby_connections: Dict[str, Set[WebSocket]] = {}

VIDEO_STORAGE = Path("/path/to/video_storage")
UPLOAD_DIR = Path("/path/to/uploads")

@app.post("/upload")
async def upload_video(file: UploadFile):
    video_id = str(uuid.uuid4())
    upload_path = UPLOAD_DIR / f"{video_id}_{file.filename}"
    
    # Save upload
    with open(upload_path, "wb") as f:
        content = await file.read()
        f.write(content)
    
    # Queue transcode (run in background)
    asyncio.create_task(transcode_video(str(upload_path), video_id))
    
    return {"video_id": video_id, "status": "processing"}

async def transcode_video(input_path: str, video_id: str):
    output_dir = VIDEO_STORAGE / video_id
    output_dir.mkdir(parents=True, exist_ok=True)
    
    cmd = [
        'ffmpeg', '-i', input_path,
        '-map', '0:v:0', '-map', '0:v:0', '-map', '0:v:0',
        '-map', '0:a:0',
        '-c:v', 'libx264', '-preset', 'fast',
        '-c:a', 'aac', '-b:a', '128k',
        '-s:v:0', '854x480', '-b:v:0', '1000k',
        '-s:v:1', '1280x720', '-b:v:1', '2500k',
        '-s:v:2', '1920x1080', '-b:v:2', '5000k',
        '-f', 'dash',
        '-seg_duration', '4',
        '-use_template', '1',
        '-use_timeline', '1',
        str(output_dir / 'manifest.mpd')
    ]
    
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE
    )
    await proc.communicate()

@app.post("/lobby/create")
async def create_lobby(video_id: str):
    lobby_id = str(uuid.uuid4())
    lobbies[lobby_id] = {
        "video_id": video_id,
        "position": 0.0,
        "state": "paused",
        "host": None
    }
    lobby_connections[lobby_id] = set()
    return {"lobby_id": lobby_id}

@app.websocket("/lobby/{lobby_id}")
async def lobby_websocket(websocket: WebSocket, lobby_id: str):
    await websocket.accept()
    
    if lobby_id not in lobbies:
        await websocket.close(code=4004)
        return
    
    lobby_connections[lobby_id].add(websocket)
    lobby = lobbies[lobby_id]
    
    # Set first joiner as host
    if lobby["host"] is None:
        lobby["host"] = websocket
    
    # Send current state to new joiner
    await websocket.send_json({
        "type": "sync",
        "position": lobby["position"],
        "state": lobby["state"]
    })
    
    try:
        while True:
            data = await websocket.receive_json()
            
            # Only host can control
            if websocket != lobby["host"]:
                continue
            
            if data["type"] == "seek":
                lobby["position"] = data["position"]
                await broadcast(lobby_id, {
                    "type": "sync",
                    "position": lobby["position"],
                    "state": lobby["state"]
                })
            
            elif data["type"] in ["play", "pause"]:
                lobby["state"] = data["type"] + "ing"
                await broadcast(lobby_id, {
                    "type": "sync",
                    "position": lobby["position"],
                    "state": lobby["state"]
                })
    
    except WebSocketDisconnect:
        lobby_connections[lobby_id].remove(websocket)
        
        # Clean up empty lobbies
        if len(lobby_connections[lobby_id]) == 0:
            del lobbies[lobby_id]
            del lobby_connections[lobby_id]
        elif lobby["host"] == websocket:
            # Transfer host to next person
            lobby["host"] = next(iter(lobby_connections[lobby_id]), None)

async def broadcast(lobby_id: str, message: dict):
    for connection in lobby_connections[lobby_id]:
        await connection.send_json(message)

@app.get("/watch/{lobby_id}")
async def watch_page(lobby_id: str):
    if lobby_id not in lobbies:
        return {"error": "Lobby not found"}
    
    video_id = lobbies[lobby_id]["video_id"]
    
    return HTMLResponse(f"""
    <!DOCTYPE html>
    <html>
    <head>
        <script src="https://cdn.dashjs.org/latest/dash.all.min.js"></script>
    </head>
    <body>
        <video id="videoPlayer" controls width="1280"></video>
        
        <script>
            const video = document.getElementById('videoPlayer');
            const player = dashjs.MediaPlayer().create();
            player.initialize(video, '/videos/{video_id}/manifest.mpd', false);
            
            const ws = new WebSocket('ws://localhost:8000/lobby/{lobby_id}');
            
            ws.onmessage = (event) => {{
                const msg = JSON.parse(event.data);
                
                if (msg.type === 'sync') {{
                    const drift = Math.abs(video.currentTime - msg.position);
                    if (drift > 0.5) {{
                        video.currentTime = msg.position;
                    }}
                    
                    if (msg.state === 'playing' && video.paused) {{
                        video.play();
                    }} else if (msg.state === 'paused' && !video.paused) {{
                        video.pause();
                    }}
                }}
            }};
            
            video.addEventListener('seeked', () => {{
                ws.send(JSON.stringify({{
                    type: 'seek',
                    position: video.currentTime
                }}));
            }});
            
            video.addEventListener('play', () => {{
                ws.send(JSON.stringify({{type: 'play'}}));
            }});
            
            video.addEventListener('pause', () => {{
                ws.send(JSON.stringify({{type: 'pause'}}));
            }});
        </script>
    </body>
    </html>
    """)