# Sample photos for the model accuracy bake-off

Drop real trail-cam photos here, grouped by species — **one subfolder per animal, named
after the species**. The folder name is the ground-truth label the bake-off compares each
model's prediction against.

Example layout:

    samples/
      wild_boar/
        img001.jpg
        img002.jpg
      red_deer/
        night_01.jpg
      fox/
        ...

Notes:
- Prefer the **black-and-white / infrared night shots** — that's the hard case we're
  validating, and where model choice matters most.
- Any common format works (JPEG/PNG); a mix is fine — format doesn't affect recognition.
- More photos per species = a more trustworthy score. Even ~10–20 per species helps.
- These images are **git-ignored** (see `.gitignore`) — they stay on your machine and are
  never committed, so real or private photos are safe.
