# QuPath Image Normalization Extension

The [QuPath](https://qupath.github.io/) Image Normalization (ImgNorm) extension enables users to seamlessly color-normalize Hematoxylin and Eosin (H&E) stained images for improved quantitative analysis. The underlying algorithm is based on the one outlined by [Macenko et al.](https://www.cs.unc.edu/~mn/sites/default/files/macenko2009.pdf)

## Installing

The ImgNorm extension is supported in QuPath v0.5 on both Windows and MacOS (ARM).

To install ImgNorm, download the latest file from releases and drag it onto the main QuPath window.

Note that ImgNorm has not been tested on Intel-based MacOS systems and may not function in this environment. This is because the extension uses Python-based executables that are precompiled specifically for Windows and MacOS ARM only.

## Using the ImgNorm Extension

1. Create a QuPath project.
2. Load the H&E images you want to normalize into the project. Ensure the project contains only H&E images.
3. Start the normalization algorithm by navigating to `Extensions > ImgNorm > Normalize images to new project`.
4. Once the algorithm completes, the QuPath interface will display the newly normalized images. You can locate the image files by navigating to the `normalized` directory in your QuPath project folder.

The above steps are also demonstrated in `example_video.mp4` in the repo.

### Tips and Troubleshooting
- For optimal performance, we recommend using a machine with at least 32 GB of RAM and minimizing background processes. This is especially true if you are attempting to normalize large images (>10 GB in .tif format).
- Process times will vary depending on the number and size of images. Large batches may take several hours or longer to complete. (For example, processing 30 large H&E images may take approximately 20 hours.)
- Keep image file names concise if possible. We have noticed that long image file names can lead to errors (notably in Windows systems).
- If any errors occur when running ImgNorm, the extension will typically skip the problematic image, normalize the remaining images, and report the error in the final dialog box. Refer to the QuPath log for more detailed error information.