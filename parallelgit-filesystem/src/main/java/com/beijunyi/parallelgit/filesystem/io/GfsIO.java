package com.beijunyi.parallelgit.filesystem.io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.beijunyi.parallelgit.filesystem.GfsDataService;
import com.beijunyi.parallelgit.filesystem.GitFileSystem;
import com.beijunyi.parallelgit.filesystem.GitPath;
import com.beijunyi.parallelgit.filesystem.utils.FileAttributeReader;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import static java.nio.file.AccessMode.EXECUTE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;

public final class GfsIO {

  @Nonnull
  private static GitPath getParent(@Nonnull GitPath child) {
    if(child.isRoot())
      throw new IllegalArgumentException(child.toString());
    GitPath parent = child.getParent();
    if(parent == null)
      throw new IllegalStateException(child.toString());
    return parent;
  }

  @Nullable
  private static Node findNode(@Nonnull GitPath path) throws IOException {
    if(!path.isAbsolute())
      throw new IllegalArgumentException(path.toString());
    Node current = path.getFileStore().getRoot();
    for(int i = 0; i < path.getNameCount(); i++) {
      GitPath name = path.getName(i);
      if(current instanceof DirectoryNode)
        current = ((DirectoryNode) current).getChild(name.toString());
      else
        return null;
    }
    return current;
  }

  @Nonnull
  private static Node getNode(@Nonnull GitPath path) throws IOException {
    Node node = findNode(path);
    if(node == null)
      throw new NoSuchFileException(path.toString());
    return node;
  }

  @Nonnull
  private static FileNode asFile(@Nullable Node node, @Nonnull GitPath path) throws NoSuchFileException, AccessDeniedException {
    if(node == null)
      throw new NoSuchFileException(path.toString());
    if(node instanceof FileNode)
      return (FileNode) node;
    throw new AccessDeniedException(path.toString());
  }

  @Nonnull
  static FileNode findFile(@Nonnull GitPath file) throws IOException {
    return asFile(findNode(file), file);
  }

  @Nonnull
  private static DirectoryNode asDirectory(@Nullable Node node, @Nonnull GitPath path) throws NotDirectoryException {
    if(node instanceof DirectoryNode)
      return (DirectoryNode) node;
    throw new NotDirectoryException(path.toString());
  }

  @Nonnull
  static DirectoryNode findDirectory(@Nonnull GitPath dir) throws IOException {
    return asDirectory(findNode(dir), dir);
  }

  @Nonnull
  private static String getFileName(@Nonnull GitPath path) throws IOException {
    GitPath name = path.getFileName();
    assert name != null;
    return name.toString();
  }

  @Nonnull
  private static byte[] readBlob(@Nullable AnyObjectId id, @Nonnull GfsDataService gds) throws IOException {
    if(id == null)
      return new byte[0];
    return gds.readBlob(id);
  }

  @Nonnull
  private static byte[] loadFileData(@Nonnull FileNode file, @Nonnull GfsDataService gds) throws IOException {
    byte[] bytes = readBlob(file.getObjectId(), gds);
    file.setBytes(bytes);
    return bytes;
  }

  @Nonnull
  public static byte[] getFileData(@Nonnull FileNode file, @Nonnull GfsDataService gds) throws IOException {
    byte[] bytes = file.getBytes();
    if(bytes == null)
      bytes = loadFileData(file, gds);
    return bytes;
  }

  private static long readBlobSize(@Nullable AnyObjectId blobId, @Nonnull GfsDataService gds) throws IOException {
    if(blobId == null)
      return 0;
    return gds.getBlobSize(blobId);
  }

  private static long loadFileSize(@Nonnull FileNode file, @Nonnull GitFileSystem gfs) throws IOException {
    long size = readBlobSize(file.getObjectId(), gfs);
    file.setSize(size);
    return size;
  }

  @Nonnull
  private static ConcurrentMap<String, Node> readTreeObject(@Nullable AnyObjectId id, @Nonnull GfsDataService gds) throws IOException {
    ConcurrentMap<String, Node> children = new ConcurrentHashMap<>();
    if(id != null) {
      byte[] treeData = gds.readBlob(id);
      CanonicalTreeParser treeParser = new CanonicalTreeParser();
      treeParser.reset(treeData);
      while(!treeParser.eof()) {
        Node child = Node.forObject(treeParser.getEntryObjectId(), treeParser.getEntryFileMode(), gds);
        child.takeSnapshot();
        children.put(treeParser.getEntryPathString(), child);
        treeParser.next();
      }
    }
    return children;
  }

  @Nonnull
  private static Map<String, Node> loadChildren(@Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    ConcurrentMap<String, Node> children = readTreeObject(dir.getObjectId(), gfs);
    dir.setChildren(children);
    dir.takeSnapshot();
    return children;
  }

  @Nonnull
  public static Map<String, Node> getChildren(@Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    Map<String, Node> children = prepareDirectory(dir, gfs).getChildren();
    assert children != null;
    return children;
  }

  @Nullable
  public static Node getChild(@Nonnull String name, @Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    return getChildren(dir, gfs).get(name);
  }

  private static void addChild(@Nonnull String name, @Nonnull Node node, @Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    if(!prepareDirectory(dir, gfs).addChild(name, node, false))
      throw new IllegalStateException();
  }

  @Nonnull
  public static Node addChild(@Nonnull String name, @Nonnull AnyObjectId id, @Nonnull FileMode mode, @Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    Node ret = Node.forObject(id, mode, gfs);
    addChild(name, ret, dir, gfs);
    return ret;
  }

  @Nonnull
  public static FileNode addChildFile(@Nonnull String name, @Nonnull byte[] bytes, @Nonnull FileMode mode, @Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    FileNode ret = FileNode.newFile(bytes, mode, gfs);
    addChild(name, ret, dir, gfs);
    return ret;
  }

  @Nonnull
  public static DirectoryNode addChildDirectory(@Nonnull String name, @Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    DirectoryNode ret = DirectoryNode.newDirectory(gfs);
    addChild(name, ret, dir, gfs);
    return ret;
  }

  public static boolean removeChild(@Nonnull String name, @Nonnull DirectoryNode dir, @Nonnull GitFileSystem gfs) throws IOException {
    return prepareDirectory(dir, gfs).removeChild(name);
  }

  public static long getSize(@Nonnull Node node, @Nonnull GitFileSystem gfs) throws IOException {
    long size;
    if(node instanceof FileNode) {
      FileNode file = (FileNode) node;
      size = file.getSize();
      if(size == -1L)
        size = loadFileSize(file, gfs);
    } else
      size = 0;
    return size;
  }

  @Nonnull
  public static GfsSeekableByteChannel newByteChannel(@Nonnull GitPath file, @Nonnull Set<OpenOption> options, @Nonnull Collection<FileAttribute> attrs) throws IOException {
    if(file.isRoot())
      throw new AccessDeniedException(file.toString());
    FileNode node;
    if(options.contains(CREATE) || options.contains(CREATE_NEW)) {
      DirectoryNode parent = findDirectory(getParent(file));
      String name = getFileName(file);
      if(options.contains(CREATE_NEW) || !parent.hasChild(name)) {
        node = FileNode.newFile(FileAttributeReader.read(attrs).isExecutable(), parent.getDataService());
        if(!parent.addChild(name, node, false))
          throw new FileAlreadyExistsException(file.toString());
      } else {
        node = asFile(parent.getChild(name), file);
      }
    } else
      node = findFile(file);
    return new GfsSeekableByteChannel(node, getDataService(file), options);
  }

  @Nonnull
  public static GfsDirectoryStream newDirectoryStream(@Nonnull GitPath dir, @Nullable DirectoryStream.Filter<? super Path> filter) throws IOException {
    return new GfsDirectoryStream(findDirectory(dir), dir, filter);
  }

  public static void createDirectory(@Nonnull GitPath dir) throws IOException {
    if(dir.isRoot())
      throw new FileAlreadyExistsException(dir.toString());
    DirectoryNode parent = findDirectory(getParent(dir));
    if(!parent.addChild(getFileName(dir), DirectoryNode.newDirectory(parent.getDataService()), false))
      throw new FileAlreadyExistsException(dir.toString());
  }

  private static void deepCopyFile(@Nonnull FileNode source, @Nonnull FileNode target) throws IOException {
    byte[] bytes = source.getBytes();
    if(bytes == null)
      bytes = readBlob(source.getObjectId(), source.getDataService());
    target.setBytes(bytes);
  }

  private static void deepCopyDirectory(@Nonnull DirectoryNode source, @Nonnull DirectoryNode target) throws IOException {
    ConcurrentMap<String, Node> children = source.getChildren();
    if(children == null)
      children = readTreeObject(source.getObjectId(), source.getDataService());
    ConcurrentMap<String, Node> clonedChildren = new ConcurrentHashMap<>();
    for(Map.Entry<String, Node> child : children.entrySet()) {
      Node clonedChild = Node.cloneNode(child.getValue(), target.getDataService());
      clonedChildren.put(child.getKey(), clonedChild);
      copyNode(child.getValue(), clonedChild);
    }
    target.setChildren(clonedChildren);
  }

  private static void copyNode(@Nonnull Node source, @Nonnull Node target) throws IOException {
    if(!source.isInitialized() && source.getObjectId() != null && target.getDataService().hasObject(source.getObjectId())) {
      target.setObject(source.getObjectId());
      return;
    }
    if(source instanceof FileNode && target instanceof FileNode)
      deepCopyFile((FileNode)source, (FileNode)target);
    else if(source instanceof DirectoryNode && target instanceof DirectoryNode)
      deepCopyDirectory((DirectoryNode)source, (DirectoryNode)target);
    else
      throw new IllegalStateException();
  }

  public static boolean copy(@Nonnull GitPath source, @Nonnull GitPath target, @Nonnull Set<CopyOption> options) throws IOException {
    Node sourceNode = getNode(source);
    if(source.equals(target))
      return false;
    if(target.isRoot())
      throw new AccessDeniedException(target.toString());
    GitPath targetParent = getParent(target);
    DirectoryNode targetDirectory = findDirectory(targetParent);
    Node targetNode = Node.cloneNode(sourceNode, getDataService(target));
    if(!targetDirectory.addChild(getFileName(target), targetNode, options.contains(REPLACE_EXISTING)))
      throw new FileAlreadyExistsException(target.toString());
    copyNode(sourceNode, targetNode);
    return true;
  }

  public static boolean move(@Nonnull GitPath source, @Nonnull GitPath target, @Nonnull Set<CopyOption> options) throws IOException {
    if(copy(source, target, options)) {
      delete(source);
      return true;
    }
    return false;
  }

  public static void delete(@Nonnull GitPath file) throws IOException {
    if(file.isRoot())
      throw new AccessDeniedException(file.toString());
    DirectoryNode parent = findDirectory(getParent(file));
    if(!parent.removeChild(getFileName(file)))
      throw new NoSuchFileException(file.toString());
  }

  public static void checkAccess(@Nonnull GitPath path, @Nonnull Set<AccessMode> modes) throws IOException {
    Node node = getNode(path);
    if(modes.contains(EXECUTE) && !node.isExecutableFile())
      throw new AccessDeniedException(path.toString());
  }

  @Nullable
  public static <V extends FileAttributeView> V getFileAttributeView(@Nonnull GitPath path, @Nonnull Class<V> type) throws IOException, UnsupportedOperationException {
    Node node = findNode(path);
    if(node != null)
      return GfsFileAttributeView.forNode(node, path.getFileSystem(), type);
    return null;
  }

  @Nonnull
  private static AnyObjectId persistFile(@Nonnull FileNode file, @Nonnull GitFileSystem gfs) throws IOException {
    byte[] bytes = file.getBytes();
    if(bytes == null)
      throw new IllegalStateException();
    return gfs.saveBlob(bytes);
  }

  @Nullable
  private static AnyObjectId persistDirectory(@Nonnull DirectoryNode dir, boolean isRoot, @Nonnull GfsDataService gds) throws IOException {
    Map<String, Node> children = dir.getChildren();
    int count = 0;
    TreeFormatter formatter = new TreeFormatter();
    if(children != null) {
      for(Map.Entry<String, Node> child : new TreeMap<>(children).entrySet()) {
        String name = child.getKey();
        Node node = child.getValue();
        AnyObjectId nodeObject = persistNode(node, false, gfs);
        if(nodeObject != null) {
          formatter.append(name, node.getMode(), nodeObject);
          count++;
        }
      }
    }
    return (isRoot || count != 0) ? gfs.saveTree(formatter) : null;
  }

  @Nullable
  private static AnyObjectId persistNode(@Nonnull Node node, boolean isRoot, @Nonnull GitFileSystem gfs) throws IOException {
    if(!node.isDirty())
      return node.getObjectId();
    AnyObjectId nodeObject;
    if(node instanceof FileNode)
      nodeObject = persistFile((FileNode) node, gfs);
    else if(node instanceof DirectoryNode)
      nodeObject = persistDirectory((DirectoryNode) node, isRoot, gfs);
    else
      throw new IllegalStateException();
    node.setObject(nodeObject);
    node.takeSnapshot();
    return nodeObject;
  }

  @Nonnull
  public static AnyObjectId persistRoot(@Nonnull GitFileSystem gfs) throws IOException {
    Node root = gfs.getFileStore().getRoot();
    AnyObjectId ret = persistNode(root, true, gfs);
    assert ret != null;
    return ret;
  }

  @Nonnull
  private static GfsDataService getDataService(@Nonnull GitPath path) {
    return path.getFileSystem().getDataService();
  }

}
