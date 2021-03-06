/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.compactions;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

@InterfaceAudience.Private
public class CompactSelection {
  private static final long serialVersionUID = 1L;
  static final Log LOG = LogFactory.getLog(CompactSelection.class);
  // the actual list - this is needed to handle methods like "sublist" correctly
  List<StoreFile> filesToCompact = new ArrayList<StoreFile>();
  // was this compaction promoted to an off-peak
  boolean isOffPeakCompaction = false;
  // CompactSelection object creation time.
  private final long selectionTime;

  public CompactSelection(List<StoreFile> filesToCompact) {
    this.selectionTime = EnvironmentEdgeManager.currentTimeMillis();
    this.filesToCompact = filesToCompact;
    this.isOffPeakCompaction = false;
  }

  public List<StoreFile> getFilesToCompact() {
    return filesToCompact;
  }

  /**
   * Removes all files from the current compaction list, and resets off peak
   * compactions is set.
   */
  public void emptyFileList() {
    filesToCompact.clear();
  }

  public boolean isOffPeakCompaction() {
    return this.isOffPeakCompaction;
  }

  public void setOffPeak(boolean value) {
    this.isOffPeakCompaction = value;
  }

  public long getSelectionTime() {
    return selectionTime;
  }

  public CompactSelection getSubList(int start, int end) {
    filesToCompact = filesToCompact.subList(start, end);
    return this;
  }

  public void clearSubList(int start, int end) {
    filesToCompact.subList(start, end).clear();
  }
}
