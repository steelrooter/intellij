/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.run.runner;

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile;
import com.google.devrel.gmscore.tools.apk.arsc.Chunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlAttribute;
import com.google.devrel.gmscore.tools.apk.arsc.XmlChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlStartElementChunk;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.ui.Messages;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks APKs to see if they are deployable and warn the user if they aren't. */
public class ApkDebugAttributeCheckingUtil {
  private static final ExtensionPointName<ProjectSpecificHelper> HELPER_EP_NAME =
      new ExtensionPointName<>("com.google.idea.blaze.ProjectSpecificDebugAttributeHelper");

  /** Provides project specific help message for apk debuggability issues. */
  public interface ProjectSpecificHelper {
    String getHelpMessage();
  }

  /** Returns true if all APKs in list are debuggable. */
  public static void checkDebugAttributesOfApks(List<File> apks) {
    ArrayList<String> nonDebuggableApkNames = new ArrayList<>();
    for (File apk : apks) {
      try {
        if (!isApkDebuggable(apk)) {
          nonDebuggableApkNames.add(apk.getName());
        }
      } catch (IOException e) {
        Logger.getInstance(ApkDebugAttributeCheckingUtil.class).error(e);
      }
    }

    if (nonDebuggableApkNames.isEmpty()) {
      return;
    }

    // Use "and" as delimiter because there won't be more than 2 APKs, so "and" makes more sense.
    showWarningMessage(String.join(" and ", nonDebuggableApkNames));
  }

  private static boolean isApkDebuggable(File apk) throws IOException {
    try (ZipFile zipFile = new ZipFile(apk)) {
      ZipEntry manifestEntry = zipFile.getEntry("AndroidManifest.xml");
      InputStream stream = zipFile.getInputStream(manifestEntry);
      BinaryResourceFile file = BinaryResourceFile.fromInputStream(stream);
      List<Chunk> chunks = file.getChunks();

      if (chunks.isEmpty()) {
        throw new IllegalArgumentException("Invalid APK, empty manifest");
      }

      if (!(chunks.get(0) instanceof XmlChunk)) {
        throw new IllegalArgumentException("APK manifest chunk[0] != XmlChunk");
      }

      XmlChunk xmlChunk = (XmlChunk) chunks.get(0);
      for (Chunk chunk : xmlChunk.getChunks().values()) {
        if (!(chunk instanceof XmlStartElementChunk)) {
          continue;
        }

        XmlStartElementChunk startChunk = (XmlStartElementChunk) chunk;
        if (startChunk.getName().equals("application")) {
          for (XmlAttribute attribute : startChunk.getAttributes()) {
            if (attribute.name().equals("debuggable")) {
              return true;
            }
          }
        }
      }
    }

    return false;
  }

  private static void showWarningMessage(String apkDescription) {
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              String message =
                  "The \"android:debuggable\" attribute is not set to \"true\" in "
                      + apkDescription
                      + ".  Debugger may not attach properly or attach at all.\n"
                      + "Please ensure \"android:debuggable\" attribute is set to true or"
                      + " overridden to true via manifest overrides.";
              for (ProjectSpecificHelper helper : HELPER_EP_NAME.getExtensions()) {
                message += "\n\n" + helper.getHelpMessage();
              }
              Messages.showWarningDialog(message, "APK Not Debuggable");
            });
  }
}
