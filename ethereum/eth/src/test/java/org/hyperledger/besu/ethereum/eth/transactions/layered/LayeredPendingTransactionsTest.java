/*
 * Copyright Besu contributors.
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
package org.hyperledger.besu.ethereum.eth.transactions.layered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions.TransactionSelectionResult.COMPLETE_OPERATION;
import static org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions.TransactionSelectionResult.CONTINUE;
import static org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions.TransactionSelectionResult.DELETE_TRANSACTION_AND_CONTINUE;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;
import static org.hyperledger.besu.ethereum.eth.transactions.layered.TransactionsLayer.RemovalReason.DROPPED;
import static org.hyperledger.besu.ethereum.eth.transactions.layered.TransactionsLayer.RemovalReason.REPLACED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.ImmutableTransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionAddedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionDroppedListener;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolMetrics;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.evm.account.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LayeredPendingTransactionsTest extends BaseTransactionPoolTest {

  protected static final int MAX_TRANSACTIONS = 5;
  protected static final int MAX_CAPACITY_BYTES = 10_000;
  protected static final int LIMITED_TRANSACTIONS_BY_SENDER = 4;
  protected static final String REMOTE = "remote";
  protected static final String LOCAL = "local";
  protected final PendingTransactionAddedListener listener =
      mock(PendingTransactionAddedListener.class);
  protected final PendingTransactionDroppedListener droppedListener =
      mock(PendingTransactionDroppedListener.class);

  private final TransactionPoolConfiguration poolConf =
      ImmutableTransactionPoolConfiguration.builder()
          .maxPrioritizedTransactions(MAX_TRANSACTIONS)
          .maxFutureBySender(MAX_TRANSACTIONS)
          .pendingTransactionsLayerMaxCapacityBytes(MAX_CAPACITY_BYTES)
          .build();

  private final TransactionPoolConfiguration senderLimitedConfig =
      ImmutableTransactionPoolConfiguration.builder()
          .maxPrioritizedTransactions(MAX_TRANSACTIONS)
          .maxFutureBySender(LIMITED_TRANSACTIONS_BY_SENDER)
          .pendingTransactionsLayerMaxCapacityBytes(MAX_CAPACITY_BYTES)
          .build();
  private LayeredPendingTransactions senderLimitedTransactions;
  private LayeredPendingTransactions pendingTransactions;
  private CreatedLayers senderLimitedLayers;
  private CreatedLayers layers;
  private TransactionPoolMetrics txPoolMetrics;

  private static BlockHeader mockBlockHeader() {
    final BlockHeader blockHeader = mock(BlockHeader.class);
    when(blockHeader.getBaseFee()).thenReturn(Optional.of(Wei.of(100)));
    return blockHeader;
  }

  private CreatedLayers createLayers(final TransactionPoolConfiguration poolConfig) {

    final BiFunction<PendingTransaction, PendingTransaction, Boolean> transactionReplacementTester =
        (t1, t2) ->
            new TransactionPoolReplacementHandler(poolConf.getPriceBump())
                .shouldReplace(t1, t2, mockBlockHeader());

    final EvictCollectorLayer evictCollector = new EvictCollectorLayer(txPoolMetrics);

    final SparseTransactions sparseTransactions =
        new SparseTransactions(
            poolConfig, evictCollector, txPoolMetrics, transactionReplacementTester);

    final ReadyTransactions readyTransactions =
        new ReadyTransactions(
            poolConfig, sparseTransactions, txPoolMetrics, transactionReplacementTester);

    final BaseFeePrioritizedTransactions prioritizedTransactions =
        new BaseFeePrioritizedTransactions(
            poolConfig,
            LayeredPendingTransactionsTest::mockBlockHeader,
            readyTransactions,
            txPoolMetrics,
            transactionReplacementTester,
            FeeMarket.london(0L));
    return new CreatedLayers(
        prioritizedTransactions, readyTransactions, sparseTransactions, evictCollector);
  }

  @BeforeEach
  public void setup() {

    txPoolMetrics = new TransactionPoolMetrics(metricsSystem);

    layers = createLayers(poolConf);
    senderLimitedLayers = createLayers(senderLimitedConfig);

    pendingTransactions = new LayeredPendingTransactions(poolConf, layers.prioritizedTransactions);

    senderLimitedTransactions =
        new LayeredPendingTransactions(
            senderLimitedConfig, senderLimitedLayers.prioritizedTransactions);
  }

  @Test
  public void returnExclusivelyLocalTransactionsWhenAppropriate() {
    final Transaction localTransaction0 = createTransaction(0, KEYS2);
    pendingTransactions.addLocalTransaction(localTransaction0, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(1);

    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(2);

    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(3);

    final List<Transaction> localTransactions = pendingTransactions.getLocalTransactions();
    assertThat(localTransactions.size()).isEqualTo(1);
  }

  @Test
  public void addRemoteTransactions() {
    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(1);

    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isEqualTo(1);

    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(2);

    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isEqualTo(2);
  }

  @Test
  public void getNotPresentTransaction() {
    assertThat(pendingTransactions.getTransactionByHash(Hash.EMPTY_TRIE_HASH)).isEmpty();
  }

  @Test
  public void getTransactionByHash() {
    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    assertTransactionPending(pendingTransactions, transaction0);
  }

  @Test
  public void evictTransactionsWhenSizeLimitExceeded() {
    final List<Transaction> firstTxs = new ArrayList<>(MAX_TRANSACTIONS);

    pendingTransactions.subscribeDroppedTransactions(droppedListener);

    for (int i = 0; i < MAX_TRANSACTIONS; i++) {
      final Account sender = mock(Account.class);
      when(sender.getNonce()).thenReturn((long) i);
      final var tx =
          createTransaction(
              i,
              Wei.of((i + 1) * 100L),
              (int) poolConf.getPendingTransactionsLayerMaxCapacityBytes() + 1,
              SIGNATURE_ALGORITHM.get().generateKeyPair());
      pendingTransactions.addRemoteTransaction(tx, Optional.of(sender));
      firstTxs.add(tx);
      assertTransactionPending(pendingTransactions, tx);
    }

    assertThat(pendingTransactions.size()).isEqualTo(MAX_TRANSACTIONS);

    final Transaction lastBigTx =
        createTransaction(
            0,
            Wei.of(100_000L),
            (int) poolConf.getPendingTransactionsLayerMaxCapacityBytes(),
            SIGNATURE_ALGORITHM.get().generateKeyPair());
    final Account lastSender = mock(Account.class);
    when(lastSender.getNonce()).thenReturn(0L);
    pendingTransactions.addRemoteTransaction(lastBigTx, Optional.of(lastSender));
    assertTransactionPending(pendingTransactions, lastBigTx);

    assertTransactionNotPending(pendingTransactions, firstTxs.get(0));
    assertThat(getRemovedCount(REMOTE, DROPPED.label(), layers.evictedCollector.name()))
        .isEqualTo(1);
    assertThat(layers.evictedCollector.getEvictedTransactions())
        .map(PendingTransaction::getTransaction)
        .contains(firstTxs.get(0));
    verify(droppedListener).onTransactionDropped(firstTxs.get(0));
  }

  @Test
  public void addTransactionForMultipleSenders() {
    final var transactionSenderA = createTransaction(0, KEYS1);
    final var transactionSenderB = createTransaction(0, KEYS2);
    assertThat(pendingTransactions.addRemoteTransaction(transactionSenderA, Optional.empty()))
        .isEqualTo(ADDED);
    assertTransactionPending(pendingTransactions, transactionSenderA);
    assertThat(pendingTransactions.addRemoteTransaction(transactionSenderB, Optional.empty()))
        .isEqualTo(ADDED);
    assertTransactionPending(pendingTransactions, transactionSenderB);
  }

  @Test
  public void dropIfTransactionTooFarInFutureForTheSender() {
    final var futureTransaction =
        createTransaction(poolConf.getTxPoolMaxFutureTransactionByAccount() + 1);
    assertThat(pendingTransactions.addRemoteTransaction(futureTransaction, Optional.empty()))
        .isEqualTo(NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER);
    assertTransactionNotPending(pendingTransactions, futureTransaction);
  }

  @Test
  public void dropAlreadyConfirmedTransaction() {
    final Account sender = mock(Account.class);
    when(sender.getNonce()).thenReturn(5L);

    final Transaction oldTransaction = createTransaction(2);
    assertThat(pendingTransactions.addRemoteTransaction(oldTransaction, Optional.of(sender)))
        .isEqualTo(ALREADY_KNOWN);
    assertThat(pendingTransactions.size()).isEqualTo(0);
    assertTransactionNotPending(pendingTransactions, oldTransaction);
  }

  @Test
  public void notifyListenerWhenRemoteTransactionAdded() {
    pendingTransactions.subscribePendingTransactions(listener);

    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());

    verify(listener).onTransactionAdded(transaction0);
  }

  @Test
  public void notifyListenerWhenLocalTransactionAdded() {
    pendingTransactions.subscribePendingTransactions(listener);

    pendingTransactions.addLocalTransaction(transaction0, Optional.empty());

    verify(listener).onTransactionAdded(transaction0);
  }

  @Test
  public void notNotifyListenerAfterUnsubscribe() {
    final long id = pendingTransactions.subscribePendingTransactions(listener);

    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());

    verify(listener).onTransactionAdded(transaction0);

    pendingTransactions.unsubscribePendingTransactions(id);

    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());

    verifyNoMoreInteractions(listener);
  }

  @Test
  public void selectTransactionsUntilSelectorRequestsNoMore() {
    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());

    final List<Transaction> parsedTransactions = new ArrayList<>();
    pendingTransactions.selectTransactions(
        transaction -> {
          parsedTransactions.add(transaction);
          return COMPLETE_OPERATION;
        });

    assertThat(parsedTransactions.size()).isEqualTo(1);
    assertThat(parsedTransactions.get(0)).isEqualTo(transaction0);
  }

  @Test
  public void selectTransactionsUntilPendingIsEmpty() {
    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());

    final List<Transaction> parsedTransactions = new ArrayList<>();
    pendingTransactions.selectTransactions(
        transaction -> {
          parsedTransactions.add(transaction);
          return CONTINUE;
        });

    assertThat(parsedTransactions.size()).isEqualTo(2);
    assertThat(parsedTransactions.get(0)).isEqualTo(transaction0);
    assertThat(parsedTransactions.get(1)).isEqualTo(transaction1);
  }

  @Test
  public void notSelectReplacedTransaction() {
    final Transaction transaction1 = createTransaction(0, KEYS1);
    final Transaction transaction1b = createTransactionReplacement(transaction1, KEYS1);

    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());
    pendingTransactions.addRemoteTransaction(transaction1b, Optional.empty());

    final List<Transaction> parsedTransactions = new ArrayList<>();
    pendingTransactions.selectTransactions(
        transaction -> {
          parsedTransactions.add(transaction);
          return CONTINUE;
        });

    assertThat(parsedTransactions).containsExactly(transaction1b);
  }

  @Test
  public void selectTransactionsFromSameSenderInNonceOrder() {
    final Transaction transaction0 = createTransaction(0, KEYS1);
    final Transaction transaction1 = createTransaction(1, KEYS1);
    final Transaction transaction2 = createTransaction(2, KEYS1);

    // add out of order
    pendingTransactions.addLocalTransaction(transaction2, Optional.empty());
    pendingTransactions.addLocalTransaction(transaction1, Optional.empty());
    pendingTransactions.addLocalTransaction(transaction0, Optional.empty());

    final List<Transaction> iterationOrder = new ArrayList<>(3);
    pendingTransactions.selectTransactions(
        transaction -> {
          iterationOrder.add(transaction);
          return CONTINUE;
        });

    assertThat(iterationOrder).containsExactly(transaction0, transaction1, transaction2);
  }

  @Test
  public void notForceNonceOrderWhenSendersDiffer() {
    final Account sender2 = mock(Account.class);
    when(sender2.getNonce()).thenReturn(1L);

    final Transaction transactionSender1 = createTransaction(0, Wei.of(10), KEYS1);
    final Transaction transactionSender2 = createTransaction(1, Wei.of(200), KEYS2);

    pendingTransactions.addLocalTransaction(transactionSender1, Optional.empty());
    pendingTransactions.addLocalTransaction(transactionSender2, Optional.of(sender2));

    final List<Transaction> iterationOrder = new ArrayList<>(2);
    pendingTransactions.selectTransactions(
        transaction -> {
          iterationOrder.add(transaction);
          return CONTINUE;
        });

    assertThat(iterationOrder).containsExactly(transactionSender2, transactionSender1);
  }

  @Test
  public void invalidTransactionIsDeletedFromPendingTransactions() {
    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    pendingTransactions.addRemoteTransaction(transaction1, Optional.empty());

    final List<Transaction> parsedTransactions = new ArrayList<>(2);
    pendingTransactions.selectTransactions(
        transaction -> {
          parsedTransactions.add(transaction);
          return DELETE_TRANSACTION_AND_CONTINUE;
        });

    assertThat(parsedTransactions.size()).isEqualTo(2);
    assertThat(parsedTransactions.get(0)).isEqualTo(transaction0);
    assertThat(parsedTransactions.get(1)).isEqualTo(transaction1);

    assertThat(pendingTransactions.size()).isZero();
  }

  @Test
  public void returnEmptyOptionalAsMaximumNonceWhenNoTransactionsPresent() {
    assertThat(pendingTransactions.getNextNonceForSender(SENDER1)).isEmpty();
  }

  @Test
  public void replaceTransactionWithSameSenderAndNonce() {
    final Transaction transaction1 = createTransaction(0, Wei.of(20), KEYS1);
    final Transaction transaction1b = createTransactionReplacement(transaction1, KEYS1);
    final Transaction transaction2 = createTransaction(1, Wei.of(10), KEYS1);
    assertThat(pendingTransactions.addRemoteTransaction(transaction1, Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(pendingTransactions.addRemoteTransaction(transaction2, Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(
            pendingTransactions
                .addRemoteTransaction(transaction1b, Optional.empty())
                .isReplacement())
        .isTrue();

    assertTransactionNotPending(pendingTransactions, transaction1);
    assertTransactionPending(pendingTransactions, transaction1b);
    assertTransactionPending(pendingTransactions, transaction2);
    assertThat(pendingTransactions.size()).isEqualTo(2);
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isEqualTo(3);
    assertThat(getRemovedCount(REMOTE, REPLACED.label(), layers.prioritizedTransactions.name()))
        .isEqualTo(1);
  }

  @Test
  public void replaceTransactionWithSameSenderAndNonce_multipleReplacements() {
    final int replacedTxCount = 5;
    final List<Transaction> replacedTransactions = new ArrayList<>(replacedTxCount);
    Transaction duplicateTx = createTransaction(0, Wei.of(50), KEYS1);
    for (int i = 0; i < replacedTxCount; i++) {
      replacedTransactions.add(duplicateTx);
      pendingTransactions.addRemoteTransaction(duplicateTx, Optional.empty());
      duplicateTx = createTransactionReplacement(duplicateTx, KEYS1);
    }

    final Transaction independentTx = createTransaction(1, Wei.ONE, KEYS1);
    assertThat(pendingTransactions.addRemoteTransaction(independentTx, Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(
            pendingTransactions.addRemoteTransaction(duplicateTx, Optional.empty()).isReplacement())
        .isTrue();

    // All txs except the last duplicate should be removed
    replacedTransactions.forEach(tx -> assertTransactionNotPending(pendingTransactions, tx));
    assertTransactionPending(pendingTransactions, duplicateTx);
    // Tx with distinct nonce should be maintained
    assertTransactionPending(pendingTransactions, independentTx);

    assertThat(pendingTransactions.size()).isEqualTo(2);
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name()))
        .isEqualTo(replacedTxCount + 2);
    assertThat(getRemovedCount(REMOTE, REPLACED.label(), layers.prioritizedTransactions.name()))
        .isEqualTo(replacedTxCount);
  }

  @Test
  public void
      replaceTransactionWithSameSenderAndNonce_multipleReplacementsAddedLocallyAndRemotely() {
    final int replacedTxCount = 5;
    final List<Transaction> replacedTransactions = new ArrayList<>(replacedTxCount);
    int remoteDuplicateCount = 0;
    Transaction replacingTx = createTransaction(0, KEYS1);
    for (int i = 0; i < replacedTxCount; i++) {
      replacedTransactions.add(replacingTx);
      if (i % 2 == 0) {
        pendingTransactions.addRemoteTransaction(replacingTx, Optional.empty());
        remoteDuplicateCount++;
      } else {
        pendingTransactions.addLocalTransaction(replacingTx, Optional.empty());
      }
      replacingTx = createTransactionReplacement(replacingTx, KEYS1);
    }

    final Transaction independentTx = createTransaction(1);
    assertThat(
            pendingTransactions.addLocalTransaction(replacingTx, Optional.empty()).isReplacement())
        .isTrue();
    assertThat(pendingTransactions.addRemoteTransaction(independentTx, Optional.empty()))
        .isEqualTo(ADDED);

    // All txs except the last duplicate should be removed
    replacedTransactions.forEach(tx -> assertTransactionNotPending(pendingTransactions, tx));
    assertTransactionPending(pendingTransactions, replacingTx);

    // Tx with distinct nonce should be maintained
    assertTransactionPending(pendingTransactions, independentTx);

    final int localDuplicateCount = replacedTxCount - remoteDuplicateCount;
    assertThat(pendingTransactions.size()).isEqualTo(2);
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name()))
        .isEqualTo(remoteDuplicateCount + 1);
    assertThat(getAddedCount(LOCAL, layers.prioritizedTransactions.name()))
        .isEqualTo(localDuplicateCount + 1);
    assertThat(getRemovedCount(REMOTE, REPLACED.label(), layers.prioritizedTransactions.name()))
        .isEqualTo(remoteDuplicateCount);
    assertThat(getRemovedCount(LOCAL, REPLACED.label(), layers.prioritizedTransactions.name()))
        .isEqualTo(localDuplicateCount);
  }

  @Test
  public void notReplaceTransactionWithSameSenderAndNonceWhenGasPriceIsLower() {
    final Transaction transaction1 = createTransaction(0, Wei.of(2));
    final Transaction transaction1b = createTransaction(0, Wei.ONE);
    assertThat(pendingTransactions.addRemoteTransaction(transaction1, Optional.empty()))
        .isEqualTo(ADDED);

    pendingTransactions.subscribePendingTransactions(listener);
    assertThat(pendingTransactions.addRemoteTransaction(transaction1b, Optional.empty()))
        .isEqualTo(REJECTED_UNDERPRICED_REPLACEMENT);

    assertTransactionNotPending(pendingTransactions, transaction1b);
    assertTransactionPending(pendingTransactions, transaction1);
    assertThat(pendingTransactions.size()).isEqualTo(1);
    verifyNoInteractions(listener);
  }

  @Test
  public void trackNextNonceForEachSender() {
    // first sender consecutive txs: 0->1->2
    final Account firstSender = mock(Account.class);
    when(firstSender.getNonce()).thenReturn(0L);
    when(firstSender.getAddress()).thenReturn(SENDER1);
    assertNoNextNonceForSender(pendingTransactions, SENDER1);
    pendingTransactions.addRemoteTransaction(createTransaction(0, KEYS1), Optional.of(firstSender));
    assertNextNonceForSender(pendingTransactions, SENDER1, 1);

    pendingTransactions.addRemoteTransaction(createTransaction(1, KEYS1), Optional.of(firstSender));
    assertNextNonceForSender(pendingTransactions, SENDER1, 2);

    pendingTransactions.addRemoteTransaction(createTransaction(2, KEYS1), Optional.of(firstSender));
    assertNextNonceForSender(pendingTransactions, SENDER1, 3);

    // second sender not in orders: 3->0->2->1
    final Account secondSender = mock(Account.class);
    when(secondSender.getNonce()).thenReturn(0L);
    when(secondSender.getAddress()).thenReturn(SENDER2);
    assertNoNextNonceForSender(pendingTransactions, SENDER2);
    pendingTransactions.addRemoteTransaction(
        createTransaction(3, KEYS2), Optional.of(secondSender));
    assertNoNextNonceForSender(pendingTransactions, SENDER2);

    pendingTransactions.addRemoteTransaction(
        createTransaction(0, KEYS2), Optional.of(secondSender));
    assertNextNonceForSender(pendingTransactions, SENDER2, 1);

    pendingTransactions.addRemoteTransaction(
        createTransaction(2, KEYS2), Optional.of(secondSender));
    assertNextNonceForSender(pendingTransactions, SENDER2, 1);

    // tx 1 will fill the nonce gap and all txs will be ready
    pendingTransactions.addRemoteTransaction(
        createTransaction(1, KEYS2), Optional.of(secondSender));
    assertNextNonceForSender(pendingTransactions, SENDER2, 4);
  }

  @Test
  public void correctNonceIsReturned() {
    final Account sender = mock(Account.class);
    when(sender.getNonce()).thenReturn(1L);
    assertThat(pendingTransactions.getNextNonceForSender(transaction2.getSender())).isEmpty();
    // since tx 3 is missing, 4 is sparse,
    // note that 0 is already known since sender nonce is 1
    addLocalTransactions(pendingTransactions, sender, 0, 1, 2, 4);
    assertThat(pendingTransactions.size()).isEqualTo(3);
    assertThat(pendingTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(3);

    // tx 3 arrives and is added, while 4 is moved to ready
    addLocalTransactions(pendingTransactions, sender, 3);
    assertThat(pendingTransactions.size()).isEqualTo(4);
    assertThat(pendingTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(5);

    // when 5 is added, the pool is full, and so 6 and 7 are dropped since too far in future
    addLocalTransactions(pendingTransactions, sender, 5, 6, 7);
    assertThat(pendingTransactions.size()).isEqualTo(5);

    // assert that transactions are pruned by account from the latest future nonce first
    assertThat(pendingTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(6);
  }

  @Test
  public void correctNonceIsReturnedForSenderLimitedPool() {
    final Account sender = mock(Account.class);
    when(sender.getNonce()).thenReturn(1L);

    assertThat(senderLimitedTransactions.getNextNonceForSender(transaction2.getSender())).isEmpty();
    // since tx 3 is missing, 4 is sparse,
    // note that 0 is already known since sender nonce is 1
    addLocalTransactions(senderLimitedTransactions, sender, 0, 1, 2, 4);
    assertThat(senderLimitedTransactions.size()).isEqualTo(3);
    assertThat(senderLimitedTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(3);

    // tx 3 arrives and is added, while 4 is moved to ready
    addLocalTransactions(senderLimitedTransactions, sender, 3);
    assertThat(senderLimitedTransactions.size()).isEqualTo(4);
    assertThat(senderLimitedTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(5);

    // for sender max 4 txs are allowed, so 5, 6 and 7 are dropped since too far in future
    addLocalTransactions(senderLimitedTransactions, sender, 5, 6, 7);
    assertThat(senderLimitedTransactions.size()).isEqualTo(4);

    // assert that we drop txs with future nonce first
    assertThat(senderLimitedTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(5);
  }

  @Test
  public void correctNonceIsReturnedWithRepeatedTransactions() {
    assertThat(pendingTransactions.getNextNonceForSender(transaction2.getSender())).isEmpty();
    final Account sender = mock(Account.class);
    addLocalTransactions(pendingTransactions, sender, 0, 1, 2, 1, 0, 4);
    assertThat(pendingTransactions.getNextNonceForSender(transaction2.getSender()))
        .isPresent()
        .hasValue(3);
    addLocalTransactions(pendingTransactions, sender, 3);
  }

  @Test
  public void shouldNotIncrementAddedCounterWhenRemoteTransactionAlreadyPresent() {
    pendingTransactions.addLocalTransaction(transaction0, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(1);
    assertThat(getAddedCount(LOCAL, layers.prioritizedTransactions.name())).isEqualTo(1);
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isZero();

    assertThat(pendingTransactions.addRemoteTransaction(transaction0, Optional.empty()))
        .isEqualTo(ALREADY_KNOWN);
    assertThat(pendingTransactions.size()).isEqualTo(1);
    assertThat(getAddedCount(LOCAL, layers.prioritizedTransactions.name())).isEqualTo(1);
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isZero();
  }

  @Test
  public void shouldNotIncrementAddedCounterWhenLocalTransactionAlreadyPresent() {
    pendingTransactions.addRemoteTransaction(transaction0, Optional.empty());
    assertThat(pendingTransactions.size()).isEqualTo(1);
    assertThat(getAddedCount(LOCAL, layers.prioritizedTransactions.name())).isZero();
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isEqualTo(1);

    assertThat(pendingTransactions.addLocalTransaction(transaction0, Optional.empty()))
        .isEqualTo(ALREADY_KNOWN);
    assertThat(pendingTransactions.size()).isEqualTo(1);
    assertThat(getAddedCount(LOCAL, layers.prioritizedTransactions.name())).isZero();
    assertThat(getAddedCount(REMOTE, layers.prioritizedTransactions.name())).isEqualTo(1);
  }

  @Test
  public void doNothingIfTransactionAlreadyPending() {
    final var addedTxs = populateCache(1, 0);
    assertThat(
            pendingTransactions.addRemoteTransaction(
                addedTxs[0].transaction, Optional.of(addedTxs[0].account)))
        .isEqualTo(ALREADY_KNOWN);
    assertTransactionPending(pendingTransactions, addedTxs[0].transaction);
  }

  @Test
  public void returnsCorrectNextNonceWhenAddedTransactionsHaveGaps() {
    final var addedTxs = populateCache(3, 0, 1);
    assertThat(pendingTransactions.getNextNonceForSender(addedTxs[0].transaction.getSender()))
        .isPresent()
        .hasValue(1);
  }

  private TransactionAndAccount[] populateCache(final int numTxs, final long startingNonce) {
    return populateCache(numTxs, KEYS1, startingNonce, OptionalLong.empty());
  }

  private TransactionAndAccount[] populateCache(
      final int numTxs, final long startingNonce, final long missingNonce) {
    return populateCache(numTxs, KEYS1, startingNonce, OptionalLong.of(missingNonce));
  }

  private TransactionAndAccount[] populateCache(
      final int numTxs,
      final KeyPair keys,
      final long startingNonce,
      final OptionalLong maybeGapNonce) {
    final List<TransactionAndAccount> addedTransactions = new ArrayList<>(numTxs);
    for (int i = 0; i < numTxs; i++) {
      final long nonce = startingNonce + i;
      if (maybeGapNonce.isEmpty() || maybeGapNonce.getAsLong() != nonce) {
        final var transaction = createTransaction(nonce, keys);
        final Account sender = mock(Account.class);
        when(sender.getNonce()).thenReturn(startingNonce);
        final var res = pendingTransactions.addRemoteTransaction(transaction, Optional.of(sender));
        assertTransactionPending(pendingTransactions, transaction);
        assertThat(res).isEqualTo(ADDED);
        addedTransactions.add(new TransactionAndAccount(transaction, sender));
      }
    }
    return addedTransactions.toArray(TransactionAndAccount[]::new);
  }

  record TransactionAndAccount(Transaction transaction, Account account) {}

  record CreatedLayers(
      AbstractPrioritizedTransactions prioritizedTransactions,
      ReadyTransactions readyTransactions,
      SparseTransactions sparseTransactions,
      EvictCollectorLayer evictedCollector) {}
}
