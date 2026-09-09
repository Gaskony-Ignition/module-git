package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.images.ImageFormat;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.images.ImageManager;
import com.inductiveautomation.ignition.gateway.images.ImageResource;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static com.operametrix.ignition.git.GatewayHook.getContext;
import static com.operametrix.ignition.git.managers.GitManager.clearDirectory;
import static com.operametrix.ignition.git.managers.GitManager.getProjectFolderPath;

public class GitImageManager {
    private final static LoggerEx logger = LoggerEx.newBuilder().build(GitImageManager.class);

    /**
     * Merge the project's image snapshot into the gateway image store.
     *
     * <p><b>Merge, not replace.</b> The store is gateway-scoped, so replacing it made one
     * project's {@code images/} folder authoritative for every other project — and a project
     * with no snapshot cleared it outright, which deleted the platform's 704 Builtin icons on
     * the first clone.
     *
     * <p>Nothing here deletes. An image dropped from the project therefore stays on the gateway.
     * That is the deliberate trade: a stale image is a tidy-up, someone else's deleted image is
     * a restore from backup.
     */
    public static void importImages(String projectName) {
        Path projectDir = getProjectFolderPath(projectName);
        File directory = projectDir.resolve("images").toFile();
        if (!directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        uploadFiles(files != null ? files : new File[0]);
    }

    protected static void uploadFiles(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                uploadFolder(file, "");
            } else {
                uploadFile(file, "");
            }
        }
    }


    protected static void uploadFile(File f, String path) {
        String lName = f.getName().toLowerCase();
        if (lName.endsWith(".png") || lName
                .endsWith(".gif") || lName
                .endsWith(".jpg") || lName
                .endsWith(".jpeg") || lName
                .endsWith(".svg"))
            try {
                String ext = lName.substring(lName.lastIndexOf(".") + 1);
                ImageFormat format = ImageFormat.forExtension(ext).orElse(null);
                if (format == null) {
                    logger.warn("Unsupported image extension '" + ext + "' for file: '" + f.getName() + "'");
                    return;
                }
                byte[] bytes = Files.readAllBytes(f.toPath());
                int width = 0;
                int height = 0;
                Image img = Toolkit.getDefaultToolkit().createImage(bytes);
                if (PathIcon.waitForImage(img)) {
                    width = img.getWidth(null);
                    height = img.getHeight(null);
                }

                // Idempotent: a pull re-imports the whole snapshot, and the gateway store
                // already holds most of it. Skipping identical bytes keeps a routine pull quiet
                // instead of logging an insert conflict per image.
                ImageManager imageManager = getContext().getImageManager();
                String fullPath = path.isEmpty() ? f.getName() : path + f.getName();
                try {
                    Optional<ImageResource> existing = imageManager.getImage(fullPath);
                    if (existing.isPresent()) {
                        if (Arrays.equals(existing.get().data().getBytes(), bytes)) {
                            return;
                        }
                        imageManager.deleteImage(fullPath);
                    }
                    imageManager.insertImage(f.getName(), "", format, path, bytes, width, height, bytes.length);
                } catch (Exception ex) {
                    logger.error("Unable to import image '" + fullPath + "'", ex);
                }
            } catch (FileNotFoundException e) {
                logger.error("FileNotFound exception for file: '" + f.getPath() + "'");
            } catch (IOException e) {
                logger.error("IOException exception for file: '" + f.getPath() + "'");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
    }


    protected static void uploadFolder(File dir, String path) {
        try {
            getContext().getImageManager().insertImageFolder(dir.getName(), path.equals("") ? null : path);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        uploadFolder(file, path + dir.getName() + "/");
                    } else {
                        uploadFile(file, path + dir.getName() + "/");
                    }
                }
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    /**
     * Write the images this project versions into {@code <project>/images}.
     *
     * <p>{@code prefix} is the folder in the gateway image store the project owns, and it is
     * opt-in: an empty prefix versions nothing and clears the folder.
     *
     * <p><b>The store is a tree and {@code getImages} lists one level of it.</b> A folder comes
     * back as an entry in its own right, with a null format and no bytes. The previous version
     * iterated the root listing and wrote each entry straight to disk, so on 8.3 it produced a
     * single empty file named after the top folder and exported no image at all — measured on
     * 8.3.8, where the whole root listing is the one entry {@code Builtin}. Hence the walk.
     */
    public static void exportImages(Path projectFolderPath, String prefix) {
        Path imageFolderPath = projectFolderPath.resolve("images");
        clearDirectory(imageFolderPath);
        try {
            Files.createDirectories(imageFolderPath);
        } catch (IOException e) {
            logger.error(e.toString(), e);
        }

        String scope = normalise(prefix);
        if (scope.isEmpty()) {
            logger.debug("No image folder configured for this project; exporting no images.");
            return;
        }

        int written = walk(getContext().getImageManager(), scope, imageFolderPath, 0, new int[] {0});
        logger.info("Exported " + written + " image(s) under '" + scope + "'.");
    }

    /** Depth cap: a store nested deeper than this is a fault, not a layout. */
    private static final int MAX_DEPTH = 12;

    /** Total entries visited, so a cycle or a pathological store cannot hang a snapshot. */
    private static final int MAX_ENTRIES = 20000;

    private static int walk(ImageManager manager, String path, Path targetRoot, int depth, int[] visited) {
        if (depth > MAX_DEPTH || visited[0] > MAX_ENTRIES) {
            return 0;
        }
        int written = 0;
        for (ImageResource entry : manager.getImages(path)) {
            visited[0]++;
            String relPath = entry.path().getPath().toString();
            if (relPath.equals(path)) {
                continue;                       // the folder listing includes itself
            }
            if (entry.format() == null) {
                written += walk(manager, relPath, targetRoot, depth + 1, visited);
                continue;
            }
            Path target = targetRoot.resolve(relPath);
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.write(target, entry.data().getBytes());
                written++;
            } catch (IOException e) {
                logger.error("Unable to export image '" + relPath + "'", e);
            }
        }
        return written;
    }

    /** Folders in the store, so a prefix can be chosen rather than typed from memory. */
    public static java.util.List<String> listFolders() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            collectFolders(getContext().getImageManager(), "", out, 0);
        } catch (Exception e) {
            logger.warn("Unable to list the image store.", e);
        }
        return out;
    }

    /** Three levels is enough to name a project's own folder without listing every icon set. */
    private static final int FOLDER_DEPTH = 3;

    private static void collectFolders(ImageManager manager, String path, java.util.List<String> out, int depth) {
        if (depth >= FOLDER_DEPTH || out.size() > 500) {
            return;
        }
        for (ImageResource entry : manager.getImages(path)) {
            String relPath = entry.path().getPath().toString();
            if (relPath.equals(path) || entry.format() != null) {
                continue;
            }
            out.add(relPath);
            collectFolders(manager, relPath, out, depth + 1);
        }
    }

    /** Trims a configured prefix to a bare store path. */
    private static String normalise(String prefix) {
        String scope = prefix == null ? "" : prefix.trim();
        while (scope.startsWith("/")) {
            scope = scope.substring(1);
        }
        while (scope.endsWith("/")) {
            scope = scope.substring(0, scope.length() - 1);
        }
        return scope;
    }
}

class PathIcon extends ImageIcon {
    protected static final Component COMP = new Component() {
    };
    private static MediaTracker tracker;
    private static int nextId;
    public static boolean waitForImage(Image image) {
        if (image == null) {
            return false;
        } else if (image instanceof BufferedImage) {
            return true;
        } else {
            int id;
            synchronized(COMP) {
                id = nextId++;
            }

            tracker.addImage(image, id);

            try {
                tracker.waitForID(id);
            } catch (InterruptedException var4) {
                System.err.println("Image loading interrupted!");
                return false;
            }

            boolean success = !tracker.isErrorID(id);
            tracker.removeImage(image, id);
            return success;
        }
    }

    static {
        tracker = new MediaTracker(COMP);
        nextId = 0;
    }
}
