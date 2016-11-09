/*
 * Copyright 2016, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import java.util.Arrays;

import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.hash.MurmurHash3;

/**
 * Implements a key-value map where the value is a simple array of coupons. Search operations are a
 * simple traverse of the consecutive coupons. Because of this, the maximum practical size of the
 * coupon array is about 8 coupons.
 *
 * <p>The map is implemented as a prime-sized, Open Address, Double Hash, with deletes and a 1-bit
 * state array. The size of this map can grow or shrink.
 *
 * @author Lee Rhodes
 * @author Alex Saydakov
 * @author Kevin Lang
 */
class CouponTraverseMap extends CouponMap {
  private final int maxCouponsPerKey_;
  private final double entrySizeBytes_;

  private int tableEntries_;
  private int capacityEntries_;
  private int numActiveKeys_;
  private int numDeletedKeys_;

  //Arrays
  private byte[] keysArr_;
  private short[] couponsArr_;

  /**
   * <ul><li>State: 0: Empty always, don't need to look at 1st coupon. Coupons could be dirty.</li>
   * <li>State: 1: Valid entry or dirty. During rebuild, look at the first coupon to determine.
   * If first coupon != 0 means valid entry; first coupon == 0: dirty (we set to 0 when deleted)</li>
   * </ul>
   */
  private byte[] stateArr_;

  private CouponTraverseMap(final int keySizeBytes, final int maxCouponsPerKey) {
    super(keySizeBytes);
    maxCouponsPerKey_ = maxCouponsPerKey;
    double byteFraction = Math.ceil(COUPON_MAP_MIN_NUM_ENTRIES / 8.0) / COUPON_MAP_MIN_NUM_ENTRIES;
    entrySizeBytes_ = keySizeBytes + maxCouponsPerKey * 2 + byteFraction;
  }

  static CouponTraverseMap getInstance(final int keySizeBytes, final int maxCouponsPerKey) {
    CouponTraverseMap map = new CouponTraverseMap(keySizeBytes, maxCouponsPerKey);
    map.tableEntries_ = COUPON_MAP_MIN_NUM_ENTRIES;
    map.keysArr_ = new byte[COUPON_MAP_MIN_NUM_ENTRIES * keySizeBytes];
    map.couponsArr_ = new short[COUPON_MAP_MIN_NUM_ENTRIES * maxCouponsPerKey];
    map.stateArr_ = new byte[(int) Math.ceil(COUPON_MAP_MIN_NUM_ENTRIES / 8.0)];
    map.capacityEntries_ = (int)(map.tableEntries_ * COUPON_MAP_GROW_TRIGGER_FACTOR);
    return map;
  }

  @Override
  double update(final byte[] key, final int coupon) {
    int entryIndex = findOrInsertKey(key);
    return findOrInsertCoupon(entryIndex, (short) coupon);
  }

  @Override
  double getEstimate(final byte[] key) {
    final int entryIndex = findKey(key);
    if (entryIndex < 0) return 0;
    return getCouponCount(entryIndex);
  }

  @Override
  void updateEstimate(final int index, final double estimate) {
    // not used in this map
  }

  /**
   * Returns entryIndex if the given key is found. If not found, returns one's complement entryIndex
   * of an empty slot for insertion, which may be over a deleted key.
   * @param key the given key
   * @return the entryIndex
   */
  @Override
  int findKey(final byte[] key) {
    final long[] hash = MurmurHash3.hash(key, SEED);
    int entryIndex = getIndex(hash[0], tableEntries_);
    int firstDeletedIndex = -1;
    final int loopIndex = entryIndex;
    do {
      if (isBitClear(stateArr_, entryIndex)) {
        return firstDeletedIndex == -1 ? ~entryIndex : ~firstDeletedIndex; // found empty or deleted
      }
      if (couponsArr_[entryIndex * maxCouponsPerKey_] == 0) { //found deleted
        if (firstDeletedIndex == -1) firstDeletedIndex = entryIndex;
      } else if (Map.arraysEqual(keysArr_, entryIndex * keySizeBytes_, key, 0, keySizeBytes_)) {
        return entryIndex; // found key
      }
      entryIndex = (entryIndex + getStride(hash[1], tableEntries_)) % tableEntries_;
    } while (entryIndex != loopIndex);
    throw new SketchesArgumentException("Key not found and no empty slots!");
  }

  @Override
  int findOrInsertKey(final byte[] key) {
    int entryIndex = findKey(key);
    if (entryIndex < 0) {
      entryIndex = ~entryIndex;
      if (isBitSet(stateArr_, entryIndex)) { // reusing slot from a deleted key
        clearCouponArea(entryIndex);
        numDeletedKeys_--;
      }
      if (numActiveKeys_ + numDeletedKeys_ + 1 > capacityEntries_) {
        resize();
        entryIndex = ~findKey(key);
        assert entryIndex >= 0;
      }
      System.arraycopy(key, 0, keysArr_, entryIndex * keySizeBytes_, keySizeBytes_);
      setBit(stateArr_, entryIndex);
      numActiveKeys_++;
    }
    return entryIndex;
  }

  @Override
  double findOrInsertCoupon(final int entryIndex, final short value) {
    final int offset = entryIndex * maxCouponsPerKey_;
    boolean wasFound = false;
    for (int i = 0; i < maxCouponsPerKey_; i++) {
      if (couponsArr_[offset + i] == 0) {
        if (wasFound) return i;
        couponsArr_[offset + i] = value;
        return i + 1;
      }
      if (couponsArr_[offset + i] == value) {
        wasFound = true;
      }
    }
    if (wasFound) return maxCouponsPerKey_;
    return -maxCouponsPerKey_;
  }

  @Override
  void deleteKey(final int entryIndex) {
    couponsArr_[entryIndex * maxCouponsPerKey_] = 0;
    numActiveKeys_--;
    numDeletedKeys_++;
    if (numActiveKeys_ > COUPON_MAP_MIN_NUM_ENTRIES
        && numActiveKeys_ < tableEntries_ * COUPON_MAP_SHRINK_TRIGGER_FACTOR) {
      resize();
    }
  }

  @Override
  int getCouponCount(final int entryIndex) {
    final int offset = entryIndex * maxCouponsPerKey_;
    for (int i = 0; i < maxCouponsPerKey_; i++) {
      if (couponsArr_[offset + i] == 0) {
        return i;
      }
    }
    return maxCouponsPerKey_;
  }

  @Override
  CouponsIterator getCouponsIterator(final byte[] key) {
    final int entryIndex = findKey(key);
    if (entryIndex < 0) return null;
    return new CouponsIterator(couponsArr_, entryIndex * maxCouponsPerKey_, maxCouponsPerKey_);
  }

  @Override
  double getEntrySizeBytes() {
    return entrySizeBytes_;
  }

  @Override
  int getTableEntries() {
    return tableEntries_;
  }

  @Override
  int getCapacityEntries() {
    return capacityEntries_;
  }

  @Override
  int getCurrentCountEntries() {
    return numActiveKeys_ + numDeletedKeys_;
  }

  @Override
  long getMemoryUsageBytes() {
    return keysArr_.length
        + (long)couponsArr_.length * Short.BYTES
        + stateArr_.length + 4 * Integer.BYTES;
  }

  @Override
  int getActiveEntries() {
    return numActiveKeys_;
  }

  @Override
  int getDeletedEntries() {
    return numDeletedKeys_;
  }

  @Override
  int getMaxCouponsPerEntry() {
    return maxCouponsPerKey_;
  }

  @Override
  int getCapacityCouponsPerEntry() {
    return maxCouponsPerKey_;
  }

  private void resize() {
    final byte[] oldKeysArr = keysArr_;
    final short[] oldCouponsArr = couponsArr_;
    final byte[] oldStateArr = stateArr_;
    final int oldSizeKeys = tableEntries_;
    tableEntries_ = Math.max(
      nextPrime((int) (numActiveKeys_ / COUPON_MAP_TARGET_FILL_FACTOR)),
      COUPON_MAP_MIN_NUM_ENTRIES
    );
    capacityEntries_ = (int)(tableEntries_ * COUPON_MAP_GROW_TRIGGER_FACTOR);
    keysArr_ = new byte[tableEntries_ * keySizeBytes_];
    couponsArr_ = new short[tableEntries_ * maxCouponsPerKey_];
    stateArr_ = new byte[(int) Math.ceil(tableEntries_ / 8.0)];
    numActiveKeys_ = 0;
    numDeletedKeys_ = 0;
    for (int i = 0; i < oldSizeKeys; i++) {
      if (isBitSet(oldStateArr, i) && oldCouponsArr[i * maxCouponsPerKey_] != 0) {
        final byte[] key = Arrays.copyOfRange(oldKeysArr, i * keySizeBytes_, i * keySizeBytes_ + keySizeBytes_);
        final int index = insertKey(key);
        System.arraycopy(oldCouponsArr, i * maxCouponsPerKey_, couponsArr_, index * maxCouponsPerKey_, maxCouponsPerKey_);
      }
    }
  }

  // for internal use during resize, so no resize check here
  private int insertKey(final byte[] key) {
    final long[] hash = MurmurHash3.hash(key, SEED);
    int entryIndex = getIndex(hash[0], tableEntries_);
    final int loopIndex = entryIndex;
    do {
      if (isBitClear(stateArr_, entryIndex)) {
        System.arraycopy(key, 0, keysArr_, entryIndex * keySizeBytes_, keySizeBytes_);
        setBit(stateArr_, entryIndex);
        numActiveKeys_++;
        return entryIndex;
      }
      entryIndex = (entryIndex + getStride(hash[1], tableEntries_)) % tableEntries_;
    } while (entryIndex != loopIndex);
    throw new SketchesArgumentException("Key not found and no empty slots!");
  }

  private void clearCouponArea(final int entryIndex) {
    final int couponAreaIndex = entryIndex * maxCouponsPerKey_;
    for (int i = 0; i < maxCouponsPerKey_; i++) {
      couponsArr_[couponAreaIndex + i] = 0;
    }
  }

}