# SpeciesNet bake-off image — separate from the DeepFaune one to avoid dependency clashes.
# CPU-only torch keeps the download small and needs no GPU.
#
# Build:  docker build -t wildalert-speciesnet services/recognition/bakeoff/speciesnet
# Run:    docker run --rm -v <samples>:/samples:ro -v sn_cache:/root wildalert-speciesnet ...

FROM python:3.12-slim

# Native libs OpenCV (pulled in by speciesnet) needs at runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# CPU-only PyTorch first so we don't pull the multi-GB CUDA build, then SpeciesNet.
RUN pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir speciesnet

WORKDIR /work
