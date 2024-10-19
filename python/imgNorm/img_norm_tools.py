import numpy as np
import cv2

np.set_printoptions(suppress=True)


def estimate_stain_vectors(tif_file, Io=240, alpha=1, beta=0.15):
    """
    Estimate stain vectors for a given H&E image.
    :param tif_file: image file path
    :param Io: transmitted light intensity
    :param alpha: tolerance for the pseudo-min and pseudo-max
    :param beta: OD threshold to remove transparent pixels
    :return: estimated colorspace and intensity vectors
    """

    tif_file = str(tif_file)
    img = cv2.imread(tif_file, 1)
    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

    # reshape to a single row of pixels
    img = img.reshape((-1, 3))

    # filter out dark pixel artifacts
    img = img[~np.any(img <= [1, 1, 1], axis=1)]

    # calculate optical density
    OD = -np.log10((img.astype(float) + 1) / Io)  # Use log10, instead of log which is what is done in matlab

    # remove transparent pixels
    ODhat = OD[~np.any(OD < beta, axis=1)]
    ODhat = ODhat[~np.any(ODhat > 1, axis=1)]  # This is not in matlab but appears to be a step done in QuPath's ESV

    #  calculate eigenvectors
    eigvals, eigvecs = np.linalg.eigh(np.cov(ODhat.T))
    # eigvecs = -eigvecs  # This is effectively redundant...

    # project on the plane spanned by the eigenvectors corresponding to the two
    # largest eigenvalues
    That = ODhat.dot(eigvecs[:, 1:3])  # Dot product

    # find the min and max vectors and project back to OD space
    phi = np.arctan2(That[:, 1], That[:, 0])
    minPhi = np.percentile(phi, alpha, method='hazen')  # If hazen doesn't work, nearest is the next best option
    maxPhi = np.percentile(phi, 100 - alpha, method='hazen')

    vMin = eigvecs[:, 1:3].dot(np.array([(np.cos(minPhi), np.sin(minPhi))]).T)
    vMax = eigvecs[:, 1:3].dot(np.array([(np.cos(maxPhi), np.sin(maxPhi))]).T)

    # a heuristic to make the vector corresponding to hematoxylin first and the
    # one corresponding to eosin second
    if vMin[0] > vMax[0]:
        HE = np.array((vMin[:, 0], vMax[:, 0])).T
    else:
        HE = np.array((vMax[:, 0], vMin[:, 0])).T

    # rows correspond to channels (RGB), columns to OD values
    Y = np.reshape(OD, (-1, 3)).T

    # determine concentrations of the individual stains
    C = np.linalg.lstsq(HE, Y, rcond=None)[0]
    # normalize stain concentrations
    maxC = np.percentile(C, 99, method='hazen', axis=1)  # If hazen doesn't work, midpoint is the next best option

    # print(f"HE: {np.round(HE, 4)}")
    # print(f"maxC: {np.round(maxC, 4)}")

    return HE, maxC


def normalize_stains(tif_file, HE, maxC,
                     HERef=np.array([[0.651, 0.216], [0.701, 0.801], [0.29, 0.558]]),
                     maxCRef=np.array([1.9705, 1.0308]), Io=240):
    """
    Color-normalize the H&E image.
    :param tif_file: the input image
    :param HE: estimated H&E color vectors of the input image
    :param maxC: estimated H&E intensity vectors of the input image
    :param HERef: target H&E color vectors of the input image
    :param maxCRef: target H&E intensity vectors of the input image
    :param Io: transmitted light intensity
    :return: transformed image, hematoxylin-only version, eosin-only version
    """

    tif_file = str(tif_file)
    img = cv2.imread(tif_file, 1)
    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

    # extract the height, width and num of channels of image
    h, w, c = img.shape

    # reshape image to multiple rows and 3 columns.
    # Num of rows depends on the image size (wxh)
    img = img.reshape((-1, 3))

    # calculate optical density
    OD = -np.log10((img.astype(float) + 1) / Io)  # Use log10, instead of log which is what is done in matlab

    # rows correspond to channels (RGB), columns to OD values
    Y = np.reshape(OD, (-1, 3)).T

    # determine concentrations of the individual stains
    C = np.linalg.lstsq(HE, Y, rcond=None)[0]

    # normalize stain concentrations
    tmp = np.divide(maxC, maxCRef)
    C2 = np.divide(C, tmp[:, np.newaxis])

    # recreate the normalized image using reference mixing matrix
    Inorm = np.multiply(Io, np.exp(-HERef.dot(C2)))
    Inorm[Inorm > 255] = 255
    Inorm = np.round(np.reshape(Inorm.T, (h, w, 3))).astype(np.uint8)

    # Separating H and E components
    H = np.multiply(Io, np.exp(np.expand_dims(-HERef[:, 0], axis=1).dot(np.expand_dims(C2[0, :], axis=0))))
    H[H > 255] = 255
    H = np.reshape(H.T, (h, w, 3)).astype(np.uint8)

    E = np.multiply(Io, np.exp(np.expand_dims(-HERef[:, 1], axis=1).dot(np.expand_dims(C2[1, :], axis=0))))
    E[E > 255] = 255
    E = np.reshape(E.T, (h, w, 3)).astype(np.uint8)

    return Inorm, H, E


def img_compare(img, img2):
    """
    Debugging only.
    :param img: ndarray representation of first image
    :param img2: ndarray representation of second image
    :return:
    """
    if False in (img == img2):
        print("The images are not the same: ")
        print(img - img2)
    else:
        print("The images are the same.")
