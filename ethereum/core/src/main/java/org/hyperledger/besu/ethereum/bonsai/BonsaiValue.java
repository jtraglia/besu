/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.hyperledger.besu.ethereum.bonsai;

import org.hyperledger.besu.ethereum.rlp.RLPOutput;

import java.util.Objects;
import java.util.function.BiConsumer;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class BonsaiValue<T> {
  private T prior;
  private T updated;
  private boolean cleared;

  public BonsaiValue(final T prior, final T updated) {
    this.prior = prior;
    this.updated = updated;
    this.cleared = false;
  }

  public BonsaiValue(final T prior, final T updated, final boolean cleared) {
    this.prior = prior;
    this.updated = updated;
    this.cleared = cleared;
  }

  public T getPrior() {
    return prior;
  }

  public T getUpdated() {
    return updated;
  }

  public BonsaiValue<T> setPrior(final T prior) {
    this.prior = prior;
    return this;
  }

  public BonsaiValue<T> setUpdated(final T updated) {
    this.cleared = updated == null;
    this.updated = updated;
    return this;
  }

  public void writeRlp(final RLPOutput output, final BiConsumer<RLPOutput, T> writer) {
    output.startList();
    writeInnerRlp(output, writer);
    output.endList();
  }

  public void writeInnerRlp(final RLPOutput output, final BiConsumer<RLPOutput, T> writer) {
    if (prior == null) {
      output.writeNull();
    } else {
      writer.accept(output, prior);
    }
    if (updated == null) {
      output.writeNull();
    } else {
      writer.accept(output, updated);
    }
    if (!cleared) {
      output.writeNull();
    } else {
      output.writeInt(1);
    }
  }

  public boolean isUnchanged() {
    return Objects.equals(updated, prior);
  }

  public void setCleared() {
    this.cleared = true;
  }

  public boolean isCleared() {
    return cleared;
  }

  @Override
  public String toString() {
    return "BonsaiValue{"
        + "prior="
        + prior
        + ", updated="
        + updated
        + ", cleared="
        + cleared
        + '}';
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BonsaiValue<?> that = (BonsaiValue<?>) o;
    return new EqualsBuilder()
        .append(cleared, that.cleared)
        .append(prior, that.prior)
        .append(updated, that.updated)
        .isEquals();
  }

  @Override
  public int hashCode() {
    return new HashCodeBuilder(17, 37).append(prior).append(updated).append(cleared).toHashCode();
  }
}
