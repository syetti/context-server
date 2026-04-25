# Context Server

This project provides an API for analyzing audio files using AssemblyAI for transcription and Hume AI for emotional analysis. It's built with Ktor (Kotlin).

## Prerequisites

- [JDK 21](https://adoptium.net/temurin/releases/?version=21)
- API Keys for:
    - [AssemblyAI](https://www.assemblyai.com/)
    - [Hume AI](https://hume.ai/)

## Getting Started

### 1. Clone the Repository

```bash
git clone <repository-url>
cd context-server
```

### 2. Set Environment Variables

The application requires two API keys to be set in your environment:

```bash
export ASSEMBLYAI_API_KEY="your_assemblyai_api_key"
export HUME_API_KEY="your_hume_api_key"
```

*Note: On Windows PowerShell, use `$env:ASSEMBLYAI_API_KEY="your_key"`.*

### 3. Build and Run

You can run the server locally using the Gradle wrapper:

```bash
./gradlew run
```

The server will start on port `8000` by default.

## API Endpoints

### POST `/analyze-audio`

Analyzes an uploaded audio file.

**Request:**
- `multipart/form-data`
- A file field (any name) containing the audio file (e.g., `.m4a`, `.mp4`, `.wav`).

**Response (Success):**
```json
{
  "status": "success",
  "utterances": [
    { "speaker": "A", "text": "Hello there." },
    { "speaker": "B", "text": "Hi, how are you?" }
  ],
  "emotions": [
    { "name": "Joy", "score": 0.85 },
    { "name": "Interest", "score": 0.72 },
    { "name": "Surprise (positive)", "score": 0.45 }
  ]
}
```

## Docker Support

A `Dockerfile` is provided for containerized deployment.

1. **Build the image:**
   ```bash
   docker build -t context-server .
   ```

2. **Run the container:**
   ```bash
   docker run -p 8000:8000 \
     -e ASSEMBLYAI_API_KEY="your_key" \
     -e HUME_API_KEY="your_key" \
     context-server
   ```
