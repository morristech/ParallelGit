package com.beijunyi.parallelgit.filesystem;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.beijunyi.parallelgit.filesystem.io.GfsFileAttributeView;
import com.beijunyi.parallelgit.filesystem.io.RootNode;
import org.eclipse.jgit.revwalk.RevCommit;

import static com.beijunyi.parallelgit.filesystem.GitFileSystemProvider.GFS;
import static com.beijunyi.parallelgit.filesystem.io.RootNode.fromCommit;
import static com.beijunyi.parallelgit.filesystem.io.RootNode.newRoot;

public class GfsFileStore extends FileStore {

  private final RootNode root;

  public GfsFileStore(@Nullable RevCommit commit, @Nonnull GfsObjectService objService) throws IOException {
    root = commit != null ? fromCommit(commit, objService) : newRoot(objService);
  }

  @Nonnull
  @Override
  public String name() {
    return GFS;
  }

  @Nonnull
  @Override
  public String type() {
    return GFS;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public long getTotalSpace() {
    return 0;
  }

  @Override
  public long getUsableSpace() {
    return Runtime.getRuntime().freeMemory();
  }

  @Override
  public long getUnallocatedSpace() {
    return 0;
  }

  @Override
  public boolean supportsFileAttributeView(@Nonnull Class<? extends FileAttributeView> type) {
    return type.isAssignableFrom(GfsFileAttributeView.Basic.class)
             || type.isAssignableFrom(GfsFileAttributeView.Posix.class) ;
  }

  @Override
  public boolean supportsFileAttributeView(@Nonnull String name) {
    switch(name) {
      case GfsFileAttributeView.Basic.BASIC_VIEW:
      case GfsFileAttributeView.Posix.POSIX_VIEW:
        return true;
      default:
        return false;
    }
  }

  @Nullable
  @Override
  public <V extends FileStoreAttributeView> V getFileStoreAttributeView(@Nonnull Class<V> type) {
    return null;
  }

  @Override
  public Object getAttribute(@Nonnull String attribute) throws IOException {
    if(attribute.equals("totalSpace"))
      return getTotalSpace();
    if(attribute.equals("usableSpace"))
      return getUsableSpace();
    if(attribute.equals("unallocatedSpace"))
      return getUnallocatedSpace();
    throw new UnsupportedOperationException("'" + attribute + "' not recognized");
  }

  @Nonnull
  public RootNode getRoot() {
    return root;
  }

}
