/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.google.common.collect.ImmutableCollection;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public class OsoSymbolsScrubber implements FileScrubber {

  private final ImmutableCollection<Path> cellRoots;

  public OsoSymbolsScrubber(ImmutableCollection<Path> cellRoots) {
    this.cellRoots = cellRoots;
  }

  @Override
  public void scrubFile(FileChannel file) throws IOException, ScrubException {
    if (!Machos.isMacho(file)) {
      return;
    }
    try {
      Machos.relativizeOsoSymbols(file, cellRoots);
    } catch (Machos.MachoException e) {
      throw new ScrubException(e.getMessage());
    }

  }

}