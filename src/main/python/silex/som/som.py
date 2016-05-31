# som.py
# 
# author:  William Benton <willb@redhat.com>
# 
# Copyright (c) 2016 Red Hat, Inc.
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#      http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


import json
from sys import float_info

import numpy

def fromJSON(file):
  with open(file) as f:
    struct = json.load(f)
    keys = [struct["moments"][key] for key in ["count", "min", "max", "m1", "m2"]]
    moments = StreamMoments(*keys)
    fdim = struct["fdim"]
    ydim = struct["ydim"]
    xdim = struct["xdim"]
    entries = [e for e in struct["entries"]]
    return SOM(xdim, ydim, fdim, entries, moments)

class StreamMoments(object):
  from sys import float_info
  def __init__(self, count=0, min=float_info.max, max=-float_info.max, m1=0.0, m2=0.0):
    self.count = count
    self.min = min
    self.max = max
    self.m1 = m1
    self.m2 = m2
  
  def __lshift__(self, sample):
    self.max = max(self.max, sample)
    self.min = min(self.min, sample)
    dev = sample - self.m1
    self.m1 = self.m1 + (dev / (count + 1))
    self.m2 = self.m2 + (dev * dev) * count / (count + 1)
    self.count += 1
    return self
  
  def mean(self):
    return self.m1
  
  def variance(self):
    return self.m2 / self.count
  
  def stddev(self):
    return math.sqrt(self.variance)

class SOM(object):
  def __init__(self, xdim=0, ydim=0, fdim=0, entries=[], mqsink=None):
    self.xdim = xdim
    self.ydim = ydim
    self.fdim = fdim
    self.entries = numpy.array(entries)
    self.mqsink = mqsink
  
  def closestWithSimilarity(self, example):
    def cossim(u, v):
      return min(1.0, max(0.0, numpy.dot(u, v) / (numpy.linalg.norm(u) * numpy.linalg.norm(v))))
    best = (0, 0.0)
    i = 0
    for arr in self.entries:
      sim = cossim(example, arr)
      if sim > best[1]:
        best = (i, sim)
      i += 1
    return best
