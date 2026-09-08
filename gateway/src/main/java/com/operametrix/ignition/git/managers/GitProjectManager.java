package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.StringPath;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonSyntaxException;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionImmutableException;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionInvalidException;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceManifest;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.resourcecollection.json.ResourceManifestSerializer;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.operametrix.ignition.git.GatewayHook.getContext;
import static com.operametrix.ignition.git.managers.GitManager.getProjectFolderPath;

public class GitProjectManager {
    private final static LoggerEx logger = LoggerEx.newBuilder().build(GitProjectManager.class);

    /** Gson configured to (de)serialize the per-resource {@code resource.json} manifest format. */
    private static final Gson RESOURCE_GSON = new GsonBuilder()
            .registerTypeAdapter(ResourceManifest.class, ResourceManifestSerializer.forProject())
            .create();

    /**
     * One row of the Versioning page's Projects tab.
     *
     * @param versioned whether {@code projects/<name>/.git} exists — the only thing that decides
     *                  it, since {@code .git/config} owns the repo and its remotes
     * @param changes   uncommitted paths, or -1 when the count could not be read
     */
    public record ProjectStatus(String name, String title, boolean versioned, String branch,
                                String remoteName, String remoteUrl, int changes, String error) {}

    /**
     * Every project on the gateway with its git state. Lists UNVERSIONED projects too: the question
     * the page answers is "is this project in git", and a project missing from a list of repos looks
     * identical to one nobody has set up yet.
     *
     * <p>Degrades per project rather than failing the page — one unreadable repo should not hide the
     * other nine, so its row carries the error instead.
     */
    public static List<ProjectStatus> listProjectStatus() {
        Path projectsDir = GitManager.getDataFolderPath().resolve("projects");
        List<ProjectStatus> out = new ArrayList<>();
        if (!Files.isDirectory(projectsDir)) {
            return out;
        }
        try (var stream = Files.list(projectsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("project.json")))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (Path dir : dirs) {
                out.add(statusOf(dir));
            }
        } catch (IOException e) {
            logger.error("Error listing projects", e);
            throw new RuntimeException(e);
        }
        return out;
    }

    private static ProjectStatus statusOf(Path dir) {
        String name = dir.getFileName().toString();
        String title = name;
        try {
            JsonObject o = RESOURCE_GSON.fromJson(
                    new String(Files.readAllBytes(dir.resolve("project.json")), StandardCharsets.UTF_8),
                    JsonObject.class);
            if (o != null && o.has("title") && !o.get("title").isJsonNull()) {
                String t = o.get("title").getAsString();
                if (!t.isBlank()) {
                    title = t;
                }
            }
        } catch (Exception ignored) {
            // A project.json we cannot parse is still a project; the folder name will do.
        }
        if (!Files.isDirectory(dir.resolve(".git"))) {
            return new ProjectStatus(name, title, false, null, null, null, 0, null);
        }
        try (org.eclipse.jgit.api.Git git = GitManager.getGit(dir)) {
            String branch = git.getRepository().getBranch();
            String remoteName = null;
            String remoteUrl = null;
            Set<String> remotes = git.getRepository().getRemoteNames();
            if (!remotes.isEmpty()) {
                remoteName = remotes.contains("origin") ? "origin" : remotes.iterator().next();
                remoteUrl = git.getRepository().getConfig().getString("remote", remoteName, "url");
            }
            var st = git.status().call();
            int changes = st.getUntracked().size() + st.getModified().size() + st.getChanged().size()
                    + st.getAdded().size() + st.getMissing().size() + st.getRemoved().size();
            return new ProjectStatus(name, title, true, branch, remoteName, remoteUrl, changes, null);
        } catch (Exception e) {
            logger.warn("Could not read the git state of project '" + name + "'", e);
            return new ProjectStatus(name, title, true, null, null, null, -1, e.getMessage());
        }
    }

    public static void importProject(String projectName) {
        ProjectManager projectManager = getContext().getProjectManager();
        Path projectDir = getProjectFolderPath(projectName);

        try {
            Set<Resource> resources = importFromFolder(projectDir, projectName);
            ResourceCollectionManifest projectManifest = loadProjectManifest(projectDir);
            projectManager.createOrReplace(projectName, projectManifest, new ArrayList<>(resources));

        } catch (ResourceCollectionInvalidException | ResourceCollectionImmutableException | IOException e) {
            logger.error("An error occurred while importing '" + projectName + "' project.", e);
            throw new RuntimeException(e);
        }
    }

    public static Set<Map.Entry<String, byte[]>> listFiles(Path projectPath) {
        Set<Map.Entry<String, byte[]>> resources = new HashSet<>();
        Stack<File> stack = new Stack<>();
        File directory = projectPath.toFile();

        stack.push(directory);

        while (!stack.empty()) {
            File current = stack.pop();

            File[] files = current.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        // Don't descend into the git metadata directory — its contents are not
                        // project resources (and the pack files can be large).
                        if (!file.getName().equals(".git")) {
                            stack.push(file);
                        }
                    } else {
                        try {
                            String path = file.getAbsolutePath().replace(projectPath.toFile().getAbsolutePath(), "");
                            path = path.substring(1);
                            path = path.replace("\\", "/");
                            // Skip non-resource files: git metadata, gateway-resource snapshots
                            // (tags/images/themes) and the project manifest (read separately by
                            // loadProjectManifest). Importing these as project resources produces an
                            // invalid resource collection that breaks the Designer project tree.
                            if (!isAnIgnitionResource(path)) {
                                continue;
                            }
                            resources.add(new AbstractMap.SimpleEntry<>(path, Files.readAllBytes(file.toPath())));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }
        return resources;
    }

    public static boolean isAnIgnitionResource(String resource) {
        return !resource.startsWith(".git")
                && !resource.startsWith("tags")
                && !resource.startsWith("images")
                && !resource.startsWith("themes")
                && !resource.equals("project.json");
    }

    public static Set<Resource> importFromFolder(Path projectPath, String projectName) throws ResourceCollectionInvalidException, IOException {
        Set<Resource> resources = new HashSet<>();
        Set<StringPath> createdFolders = new HashSet<>();

        Set<Map.Entry<String, byte[]>> files = listFiles(projectPath);
        Map<String, List<Map.Entry<String, byte[]>>> filesByDir = files.stream()
                .collect(Collectors.groupingBy(e -> StringUtils.substringBeforeLast(e.getKey(), "/")));

        // A directory must be imported as a folder — not a leaf resource — when either:
        //   (a) it is a module folder (a depth-1 path that is just a module id, e.g.
        //       "com.inductiveautomation.perspective"): a real resource always carries a type, so a
        //       depth-1 path is never a leaf; or
        //   (b) it is an ancestor of another resource directory (it has children).
        // 8.3 writes folder-level resource.json manifests (module/type/organizational folders), so the
        // mere presence of a resource.json no longer implies a leaf. Importing such a path as a leaf
        // yields a non-folder where Ignition expects a folder — e.g. the save-time
        // "non-folder in the way at 'com.inductiveautomation.perspective'" error. Note (a) is needed in
        // addition to (b) because a fresh module folder can exist on disk with no children yet (the
        // child, such as Perspective session-props, is created on the first save).
        Set<StringPath> folderPaths = new HashSet<>();
        for (String resourcePath : filesByDir.keySet()) {
            StringPath ancestor = StringPath.parse(resourcePath).getParentPath();
            while (ancestor != null && ancestor.getPathLength() > 0) {
                folderPaths.add(ancestor);
                ancestor = ancestor.getParentPath();
            }
        }

        filesByDir.forEach((resourcePath, listOfFileNodes) -> {
                    StringPath stringPath = StringPath.parse(resourcePath);
                    resources.addAll(createParentFolderResources(projectName, stringPath, createdFolders));
                    String manifestPath = String.format("%s/%s", resourcePath, "resource.json");

                    ResourceManifest resourceManifest = removeResourceManifest(manifestPath, listOfFileNodes);

                    boolean isFolder = stringPath.getPathLength() <= 1 || folderPaths.contains(stringPath);

                    if (resourceManifest != null && !isFolder) {

                        Map<String, byte[]> dataMap = createDataMap(resourceManifest, listOfFileNodes);

                        resources.add(createResourceBuilder(projectName, stringPath, resourceManifest, dataMap).build());
                    } else if (!createdFolders.contains(stringPath)) {
                        resources.add(createResourceBuilder(projectName, stringPath, ResourceManifest.newBuilder().build(), new HashMap<>()).setFolder(true).build());
                        createdFolders.add(stringPath);
                    }
                });

        return resources;
    }

    private static ResourceManifest removeResourceManifest(String manifestPath, List<Map.Entry<String, byte[]>> listOfFileNodes) {
        return listOfFileNodes.stream()
                .filter(e -> manifestPath.equals(e.getKey()))
                .findFirst()
                .map(entry -> {
                    listOfFileNodes.remove(entry);
                    try {
                        return RESOURCE_GSON.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), ResourceManifest.class);
                    } catch (JsonSyntaxException e) {
                        logger.infof("Malformed resource.json at %s, unable to remove", entry.getKey(), e);
                        return null;
                    }
                }).orElse(null);
    }


    private static List<Resource> createParentFolderResources(String projectName, StringPath resourcePath, Set<StringPath> ignoreList) {
        List<Resource> folders = new ArrayList<>();
        StringPath currentPath = resourcePath.getParentPath();
        while (currentPath != null && currentPath.getPathLength() > 0 &&
                !ignoreList.contains(currentPath)) {
            folders.add(createResourceBuilder(projectName, currentPath,
                    ResourceManifest.newBuilder().build(), new HashMap<>())
                    .setFolder(true)
                    .build());
            ignoreList.add(currentPath);
            currentPath = currentPath.getParentPath();
        }

        return folders;
    }

    private static Map<String, byte[]> createDataMap(ResourceManifest resourceManifest, List<Map.Entry<String, byte[]>> listOfFileNodes) {
        List<String> allowedFiles = resourceManifest.getFiles();
        HashMap<String, byte[]> dataMap = new HashMap<>();
        listOfFileNodes.forEach(e -> {
            String filename = StringUtils.substringAfterLast(e.getKey(), "/");
            if (allowedFiles.contains(filename))
                dataMap.put(filename, e.getValue());
        });
        return dataMap;
    }

    private static ResourceBuilder createResourceBuilder(String projectName, StringPath resourcePath, ResourceManifest manifest, Map<String, byte[]> dataMap) {
        String moduleId = resourcePath.getPathComponent(0);
        String resourceType = (resourcePath.getPathLength() > 1) ? resourcePath.getPathComponent(1) : null;
        String subPath = (resourcePath.getPathLength() > 2) ? resourcePath.subPath().subPath().toString() : "";
        return Resource.newBuilder()
                .setResourceCollectionName(projectName)
                .setResourcePath(new ResourcePath(new ResourceType(moduleId, resourceType), subPath))
                .setDataBytes(dataMap)
                .setRestricted(manifest.isRestricted())
                .setAttributes(manifest.getAttributes())
                .setApplicationScope(manifest.getScope())
                .setDocumentation(manifest.getDocumentation())
                .setVersion(manifest.getVersion())
                .setOverridable(manifest.isOverridable());
    }

    public static ResourceCollectionManifest loadProjectManifest(Path projectPath) throws IOException {
        String json = new String(Files.readAllBytes(projectPath.resolve("project.json")), StandardCharsets.UTF_8);
        JsonObject o = RESOURCE_GSON.fromJson(json, JsonObject.class);

        String title = (o.has("title") && !o.get("title").isJsonNull())
                ? o.get("title").getAsString() : projectPath.getFileName().toString();
        String description = (o.has("description") && !o.get("description").isJsonNull())
                ? o.get("description").getAsString() : "";
        boolean enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
        boolean inheritable = o.has("inheritable") && o.get("inheritable").getAsBoolean();
        String parent = (o.has("parent") && !o.get("parent").isJsonNull())
                ? o.get("parent").getAsString() : "";

        return new ResourceCollectionManifest(title, description, enabled, inheritable, parent);
    }

}
