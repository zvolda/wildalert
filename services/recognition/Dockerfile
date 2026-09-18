# Container for the Python recognition service — runs the real DeepFaune model (CPU build).
# Self-contained; build context is this service directory:
#   docker build -t wildalert-recognition services/recognition
#   docker run -p 8084:8084 wildalert-recognition             # HTTP API (/recognize)
#   docker run wildalert-recognition python -m app.worker     # Kafka worker
#
# Python 3.12 (not 3.14): torch/PytorchWildlife have no 3.14 wheels yet. Local dev/tests run
# the stub on the host Python; only this image runs the model.
# GPU note: CPU image. A GPU build would swap the base for a CUDA runtime.

FROM python:3.12-slim

# Native libs: OpenCV (PytorchWildlife) needs libgl1/libglib2.0-0; the bioacoustics import
# chain PytorchWildlife eagerly loads needs libsndfile1.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# CPU-only PyTorch first (from the CPU wheel index) so we don't pull the multi-GB CUDA build,
# then PytorchWildlife (MegaDetector + DeepFaune) and the audio deps its imports require.
RUN pip install --no-cache-dir torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir PytorchWildlife soundfile librosa

# Bake model weights into the image so the container starts fast (no cold-start download).
RUN python -c "from PytorchWildlife.models.detection import MegaDetectorV6; \
from PytorchWildlife.models.classification import DeepfauneClassifier; \
MegaDetectorV6(device='cpu', pretrained=True, version='MDV6-yolov9-c'); \
DeepfauneClassifier(device='cpu', class_name_lang='en')"

# Service deps (FastAPI, boto3, confluent-kafka). Installed after the weights so changing them
# doesn't invalidate the multi-GB torch + model-weight layers above.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Application code.
COPY app ./app

# Use the real model in the shipped container (host/tests default to the stub).
ENV RECOGNITION_CLASSIFIER=deepfaune

EXPOSE 8084
# Shell form so Cloud Run's $PORT is expanded; locally it falls back to 8084.
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8084}
