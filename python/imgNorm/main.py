import glob
import sys
import os
import json
import base64
from pathlib import Path
from PIL import Image
import numpy as np
from img_norm_tools import estimate_stain_vectors
from img_norm_tools import normalize_stains

"""
Code and algorithm adapted from: 
    1)  Macenko, M., Niethammer, M., Marron, J. S., Borland, D., Woosley, J. T., Guan, 
        X., ... & Thomas, N. E. (2009, June). A method for normalizing histology slides for quantitative analysis. 
        In 2009 IEEE international symposium on biomedical imaging: from nano to macro (pp. 1107-1110). IEEE. 
    2)  Python for Microscopists video series (Sreenivas Bhattiprolu)
    3)  Original MATLAB code: https://github.com/mitkovetta/staining-normalization/blob/master/normalizeStaining.m
"""


def run_normalize(directory: Path, Io_val=240, alpha_val=2, beta_val=0.05,
                  HEref_arr=None,
                  maxCRef_arr=None):
    """
    Color normalize all .tif/.tiff images in a given directory.
    NOTE: This function is I/O bound. Multiprocessing may or may not work, depending on the system.
    :param directory: the provided directory. The directory needs to have one "reference"
        image to extract its stain vectors, which will be applied to normalize the rest of
        the images in the same directory.
    :param Io_val: transmitted light intensity
    :param alpha_val: tolerance for the pseudo-min and pseudo-max
    :param beta_val: OD threshold to remove transparent pixels
    :param HEref_arr: target H&E colorspace to transform the image
    :param maxCRef_arr: target H&E intensity to transform the image
    :return:
    """
    if maxCRef_arr is None:
        maxCRef_arr = [1.35, 0.75]
    if HEref_arr is None:
        HEref_arr = [[0.651, 0.216], [0.701, 0.801], [0.29, 0.558]]

    HE, maxC = None, None

    img_files_orig = [Path(file) for file in glob.glob(str(directory / '*.tif'))] \
                     + [Path(file) for file in glob.glob(str(directory / '*.tiff'))]

    # Do a first-pass loop just to get the HE and maxC of the reference
    for i, img_file in enumerate(img_files_orig, start=1):
        if img_file.stem == "reference":
            print(f"Extracting reference vectors for {directory.stem}...", flush=True)
            HE, maxC = estimate_stain_vectors(img_file, Io=Io_val, alpha=alpha_val, beta=beta_val)
            print(f"Extracted reference HE for {directory.stem}: {np.round(HE, 4)}", flush=True)
            print(f"Extracted reference maxC for {directory.stem}: {np.round(maxC, 4)}", flush=True)
            break

    if len(img_files_orig) == 0:
        raise RuntimeError(f"ERROR: {img_files_orig} was found to be empty. "
                           f"This may happen even when the file/folder exists, "
                           f"especially if it has a long name.")

    if HE is None or maxC is None:
        raise FileNotFoundError(f"ERROR: Reference file not found in img_files_orig")  # This shouldn't happen...

    count = 0

    # Normalize the patches
    for img_file in img_files_orig:
        if img_file.stem == "reference":
            print(f"Reference file skipped...", flush=True)
            continue
        count += 1
        print(f"Normalizing patch {count} of {len(img_files_orig) - 1} for {directory.stem}", flush=True)
        Inorm, _, _ = normalize_stains(img_file, HE, maxC,
                                       HERef=np.array(HEref_arr),
                                       maxCRef=np.array(maxCRef_arr))
        image = Image.fromarray(Inorm, 'RGB')
        image.save(f"{img_file.parent}/{img_file.stem}.tif")  # Can use img_file.resolve()


if __name__ == "__main__":
    try:
        json_str_dirs_encoded = sys.argv[1]
        json_str_dirs_decoded = base64.b64decode(json_str_dirs_encoded)
        json_str_dirs_raw = json_str_dirs_decoded.decode('utf-8')
        json_str_dirs_parsed: list[str] = json.loads(json_str_dirs_raw)
        dir_orig_list: list[Path] = [Path(d) for d in json_str_dirs_parsed]

        for dir_orig in dir_orig_list:
            try:
                run_normalize(dir_orig)
            except Exception as e:
                print(f"Failed to normalize in {dir_orig}: {e}", flush=True)
                with open(os.path.join(dir_orig, "ERROR.txt"), 'w') as error_flag:
                    error_flag.write(f"An error occurred: {e}")

            print(f"Next")

    except Exception as e:
        print(e)
        exit(1)
