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
 */
package org.hyperledger.besu.evm.operations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.gascalculator.SpuriousDragonGasCalculator;
import org.hyperledger.besu.evm.operation.ShrOperation;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ShrOperationTest {
  private final String number;
  private final String shift;
  private final String expectedResult;

  private final GasCalculator gasCalculator = new SpuriousDragonGasCalculator();
  private final ShrOperation operation = new ShrOperation(gasCalculator);

  private static final String[][] testData = {
    {
      "0x0000000000000000000000000000000000000000000000000000000000000001",
      "0x00",
      "0x0000000000000000000000000000000000000000000000000000000000000001"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000001",
      "0x01",
      "0x0000000000000000000000000000000000000000000000000000000000000000"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000002",
      "0x01",
      "0x0000000000000000000000000000000000000000000000000000000000000001"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000004",
      "0x01",
      "0x0000000000000000000000000000000000000000000000000000000000000002"
    },
    {
      "0x000000000000000000000000000000000000000000000000000000000000000f",
      "0x01",
      "0x0000000000000000000000000000000000000000000000000000000000000007"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000008",
      "0x01",
      "0x0000000000000000000000000000000000000000000000000000000000000004"
    },
    {
      "0x8000000000000000000000000000000000000000000000000000000000000000",
      "0xff",
      "0x0000000000000000000000000000000000000000000000000000000000000001"
    },
    {"0x8000000000000000000000000000000000000000000000000000000000000000", "0x100", "0x"},
    {"0x8000000000000000000000000000000000000000000000000000000000000000", "0x101", "0x"},
    {
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "0x0",
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    },
    {
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "0x01",
      "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    },
    {
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "0xff",
      "0x0000000000000000000000000000000000000000000000000000000000000001"
    },
    {"0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "0x100", "0x"},
    {
      "0x0000000000000000000000000000000000000000000000000000000000000000",
      "0x01",
      "0x0000000000000000000000000000000000000000000000000000000000000000"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000400",
      "0x80",
      "0x0000000000000000000000000000000000000000000000000000000000000000"
    },
    {"0x0000000000000000000000000000000000000000000000000000000000000400", "0x8000", "0x"},
    {"0x0000000000000000000000000000000000000000000000000000000000000400", "0x80000000", "0x"},
    {
      "0x0000000000000000000000000000000000000000000000000000000000000400",
      "0x8000000000000000",
      "0x"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000400",
      "0x80000000000000000000000000000000",
      "0x"
    },
    {
      "0x0000000000000000000000000000000000000000000000000000000000000400",
      "0x8000000000000000000000000000000000000000000000000000000000000000",
      "0x"
    }
  };

  @Parameterized.Parameters(name = "{index}: {0}, {1}, {2}")
  public static Iterable<String[]> data() {
    return Arrays.asList(testData);
  }

  public ShrOperationTest(final String number, final String shift, final String expectedResult) {
    this.number = number;
    this.shift = shift;
    this.expectedResult = expectedResult;
  }

  @Test
  public void shiftOperation() {
    final MessageFrame frame = mock(MessageFrame.class);
    when(frame.stackSize()).thenReturn(2);
    when(frame.getRemainingGas()).thenReturn(100L);
    when(frame.popStackItem())
        .thenReturn(UInt256.fromBytes(Bytes32.fromHexStringLenient(shift)))
        .thenReturn(UInt256.fromHexString(number));
    operation.execute(frame, null);
    verify(frame).pushStackItem(Bytes.fromHexString(expectedResult));
  }
}
