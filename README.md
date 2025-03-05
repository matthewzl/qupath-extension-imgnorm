# QuPath Image Normalization Extension

The [QuPath](https://qupath.github.io/) Image Normalization (ImgNorm) extension enables users to color-normalize Hematoxylin and Eosin (H&E) stained images for improved quantitative analysis. The underlying algorithm is based on the one by [Macenko et al.](https://www.cs.unc.edu/~mn/sites/default/files/macenko2009.pdf)

## Installing

The ImgNorm extension is supported in QuPath v0.5 on both Windows and MacOS (ARM).

To install ImgNorm, download the latest file from releases and drag it onto the main QuPath window.

> **Note:** ImgNorm has not been tested on Intel-based MacOS systems and may not function in this environment. This is because the extension uses Python-based executables that are precompiled specifically for Windows and MacOS ARM only.

## Using the ImgNorm Extension

1. Create a QuPath project.
2. Load the desired H&E images into the project.
3. Begin normalization by navigating to `Extensions > ImgNorm > Normalize images to new project`.
4. Upon completion, the QuPath interface will display the newly normalized images. You can locate the image files by navigating to the `normalized` directory in your QuPath project folder.

> **Note:** You can optionally add annotations classified as 'Ignore*' onto the pre-normalized H&E images. This will prompt the normalization algorithm to ignore these areas and crop them out when creating the normalized images. This feature can be useful if the H&E images in question contain artifacts that may negatively affect normalization (e.g., ink, blood, etc.).

The above steps are also demonstrated in `example_video.mp4` in the repo.

### Tips and Troubleshooting
- For optimal performance, we recommend using a machine with at least 32 GB of RAM and minimizing background processes. This is especially true if you are attempting to normalize large images (>10 GB in .tif format).
- Process times will vary depending on the number and size of images. Large batches may take several hours or longer to complete.
- Keep image file names concise. We have noticed that long image file names can lead to errors (notably in Windows systems).
- If any errors occur when running ImgNorm, the extension will attempt to skip the problematic image and report the error in the final dialog box. Refer to the QuPath log for more detailed error information.