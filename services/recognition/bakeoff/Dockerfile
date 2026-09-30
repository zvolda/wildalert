# Bake-off image: runs camera-trap models over the sample photos to compare accuracy.
# Separate from the service image — this is a one-time evaluation tool, not shipped.
# CPU-only torch keeps the download small and needs no GPU.
#
# Build:  docker build -t wildalert-bakeoff services/recognition/bakeoff
# Run:    docker run --rm -v <samples>:/samples:ro -v pw_cache:/root/.cache wildalert-bakeoff

FROM python:3.12-slim

# Native libs OpenCV (pulled in by PytorchWildlife) needs at runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# CPU-only PyTorch first (from the CPU wheel index) so we don't pull the multi-GB CUDA build,
# then PytorchWildlife (MegaDetector + DeepFaune + more) on top.
# torchaudio included and version-matched: PytorchWildlife eagerly imports a bioacoustics
# module that loads torchaudio's native extension, which fails if versions don't match.
RUN pip install --no-cache-dir torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir PytorchWildlife

# PytorchWildlife's top-level import pulls in a bioacoustics module that needs soundfile
# (which needs the native libsndfile). Added last so the heavy torch layer stays cached.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libsndfile1 \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir soundfile librosa

WORKDIR /work
