# Container for the Python recognition service (CPU build). Self-contained — build context
# is this service directory:
#   docker build -t wildalert-recognition services/recognition
#   docker run -p 8084:8084 wildalert-recognition
# Built in the cloud at deploy time (Cloud Run / Railway); not required for local dev.
#
# GPU note: this is a CPU image. When SpeciesNet/DeepFaune inference is added, a GPU build
# would swap the base image for a CUDA runtime and install GPU-enabled model deps.

FROM python:3.14-slim

WORKDIR /app

# Install runtime deps first so this layer is cached unless requirements change.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Application code.
COPY app ./app

EXPOSE 8084
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
