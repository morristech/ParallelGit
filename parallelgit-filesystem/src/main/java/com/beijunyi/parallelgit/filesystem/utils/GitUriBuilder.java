package com.beijunyi.parallelgit.filesystem.utils;

import java.io.File;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.beijunyi.parallelgit.filesystem.GitFileSystem;
import com.beijunyi.parallelgit.filesystem.GitFileSystemProvider;
import com.beijunyi.parallelgit.filesystem.GitPath;
import org.eclipse.jgit.lib.Repository;

public class GitUriBuilder {

  private String repository;
  private String file;
  private final Map<String, String> params = new LinkedHashMap<>();

  @Nonnull
  public static GitUriBuilder prepare() {
    return new GitUriBuilder();
  }

  @Nonnull
  public static GitUriBuilder forFileSystem(@Nonnull GitFileSystem gfs) {
    return prepare()
             .repository(gfs.getRepository())
             .session(gfs.getSessionId());
  }

  @Nonnull
  public GitUriBuilder session(@Nullable String session) {
    if(session != null)
      params.put(GitUriUtils.SID_KEY, session);
    else
      params.remove(GitUriUtils.SID_KEY);
    return this;
  }

  @Nonnull
  public GitUriBuilder repository(@Nullable String repoDirPath) {
    this.repository = repoDirPath;
    return this;
  }

  @Nonnull
  public GitUriBuilder repository(@Nullable File repoDir) {
    return repository(repoDir != null ? repoDir.toURI().getPath() : null);
  }

  @Nonnull
  public GitUriBuilder repository(@Nullable Repository repository) {
    return repository(repository != null
                        ? (repository.isBare() ? repository.getDirectory() : repository.getWorkTree())
                        : null);
  }

  @Nonnull
  public GitUriBuilder file(@Nullable String filePathStr) {
    this.file = filePathStr;
    return this;
  }

  @Nonnull
  public GitUriBuilder file(@Nullable GitPath filePath) {
    return file(filePath != null ? filePath.toRealPath().toString() : null);
  }

  @Nonnull
  private String buildPath() {
    if(repository == null)
      throw new IllegalArgumentException("Missing repository");
    if(!repository.startsWith("/"))
      throw new IllegalArgumentException("Repository location must be an absolute path");
    return GitFileSystemProvider.GIT_FS_SCHEME + ":" + repository;
  }

  @Nonnull
  private String buildQuery() {
    String query = "";
    for(Map.Entry<String, String> param : params.entrySet()) {
      if(!query.isEmpty())
      query += "&";
      query += param.getKey() + "=" + param.getKey();
    }
    return query;
  }

  @Nonnull
  private String buildFragment() {
    String fragment = "";
    if(file != null && !file.isEmpty() && !file.equals("/")) {
      if(!file.startsWith("/"))
        fragment += "/";
      fragment += file;
    }
    return fragment;
  }

  @Nonnull
  public URI build() {
    String ret = buildPath();
    String query = buildQuery();
    if(!query.isEmpty())
      ret += "?" + query;
    String fragment = buildFragment();
    if(!fragment.isEmpty())
      ret += "#" + fragment;
    return URI.create(ret);
  }
}