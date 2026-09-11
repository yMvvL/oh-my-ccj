package com.ccj.agent.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The desktop's own folder chooser.
 *
 * <p>It exists because a browser cannot supply an absolute path: the web File System Access API hands
 * back a directory handle with a name and nothing else, deliberately. The server, on the other hand,
 * runs on the machine the user is sitting at, so it can ask the desktop — which is exactly what a
 * local tool should do.
 *
 * <p>Injectable so tests never open a window, and so a headless deployment can refuse clearly.
 */
public interface FolderChooser {

  /**
   * Shows a modal chooser and returns the picked directory.
   *
   * @return the chosen directory, or empty when the user cancelled or the chooser timed out
   * @throws IOException when no chooser can be shown at all, with a message worth showing the user
   */
  Optional<Path> choose(String title) throws IOException;
}
