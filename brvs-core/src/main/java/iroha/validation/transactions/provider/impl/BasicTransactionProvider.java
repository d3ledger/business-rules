package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.listener.IrohaReliableChainListener;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.UserQuorumProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.BlockStorage;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final TransactionVerdictStorage transactionVerdictStorage;
  private final CacheProvider cacheProvider;
  private final UserQuorumProvider userQuorumProvider;
  private final RegistrationProvider registrationProvider;
  private final BlockStorage blockStorage;
  private final IrohaReliableChainListener irohaReliableChainListener;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);
  private final Set<String> userDomains;
  private boolean isStarted;

  public BasicTransactionProvider(
      TransactionVerdictStorage transactionVerdictStorage,
      CacheProvider cacheProvider,
      UserQuorumProvider userQuorumProvider,
      RegistrationProvider registrationProvider,
      BlockStorage blockStorage,
      IrohaReliableChainListener irohaReliableChainListener,
      String userDomains
  ) {
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");
    Objects.requireNonNull(cacheProvider, "CacheProvider must not be null");
    Objects.requireNonNull(userQuorumProvider, "UserQuorumProvider must not be null");
    Objects.requireNonNull(registrationProvider, "RegistrationProvider must not be null");
    Objects
        .requireNonNull(irohaReliableChainListener, "IrohaReliableChainListener must not be null");
    if (Strings.isNullOrEmpty(userDomains)) {
      throw new IllegalArgumentException("User domains string must not be null nor empty");
    }

    this.transactionVerdictStorage = transactionVerdictStorage;
    this.cacheProvider = cacheProvider;
    this.userQuorumProvider = userQuorumProvider;
    this.registrationProvider = registrationProvider;
    this.blockStorage = blockStorage;
    this.irohaReliableChainListener = irohaReliableChainListener;
    this.userDomains = Arrays.stream(userDomains.split(",")).collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Observable<Transaction> getPendingTransactionsStreaming() {
    if (!isStarted) {
      logger.info("Starting pending transactions streaming");
      executorService.scheduleAtFixedRate(this::monitorIrohaPending, 0, 2, TimeUnit.SECONDS);
      executorService.schedule(this::processBlockTransactions, 0, TimeUnit.SECONDS);
      executorService.schedule(this::processRejectedTransactions, 0, TimeUnit.SECONDS);
      isStarted = true;
    }
    return cacheProvider.getObservable();
  }

  private void monitorIrohaPending() {
    irohaReliableChainListener
        .getAllPendingTransactions(registrationProvider.getRegisteredAccounts())
        .forEach(transaction -> {
              // if only BRVS signatory remains
              if (transaction.getSignaturesCount() >= userQuorumProvider.getUserQuorumDetail(
                  transaction.getPayload().getReducedPayload().getCreatorAccountId())) {
                String hex = ValidationUtils.hexHash(transaction);
                if (!transactionVerdictStorage.isHashPresentInStorage(hex)) {
                  transactionVerdictStorage.markTransactionPending(hex);
                  cacheProvider.put(transaction);
                }
              }
            }
        );
  }

  private void processRejectedTransactions() {
    transactionVerdictStorage.getRejectedTransactionsHashesStreaming()
        .subscribe(this::tryToRemoveLock);
  }

  private void processBlockTransactions() {
    irohaReliableChainListener.getBlockStreaming().subscribe(block -> {
          /*
          We do not process rejected hashes of blocks in order to support fail fast behavior
          BRVS fake key pair leads to STATELESS_INVALID status so such transactions
          are not presented in ledger blocks at all
           */
          // Store new block first
          blockStorage.store(block);
          processCommitted(
              block
                  .getBlockV1()
                  .getPayload()
                  .getTransactionsList()
          );
        }
    );
  }

  private void processCommitted(List<Transaction> blockTransactions) {
    if (blockTransactions != null) {
      blockTransactions.forEach(transaction -> {
            tryToRemoveLock(transaction);
            try {
              modifyUserQuorumIfNeeded(transaction);
              registerCreatedAccountByTransactionScanning(transaction);
            } catch (Exception e) {
              logger.warn("Couldn't process account changes from the committed block", e);
            }
          }
      );
    }
  }

  private void modifyUserQuorumIfNeeded(Transaction blockTransaction) {
    final String creatorAccountId = blockTransaction.getPayload().getReducedPayload()
        .getCreatorAccountId();

    final long createdTime = blockTransaction.getPayload().getReducedPayload().getCreatedTime();
    final long syncTime = createdTime - createdTime % 1000000;
    blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(command -> userDomains.contains(getDomain(creatorAccountId)))
        .filter(Command::hasAddSignatory)
        .map(Command::getAddSignatory)
        .forEach(command -> {
          userQuorumProvider
              .setUserQuorumDetail(creatorAccountId,
                  userQuorumProvider.getUserQuorumDetail(creatorAccountId) + 1, syncTime);
          userQuorumProvider.setUserAccountQuorum(creatorAccountId,
              userQuorumProvider.getValidQuorumForUserAccount(creatorAccountId), syncTime);
        });

    blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(command -> userDomains.contains(getDomain(creatorAccountId)))
        .filter(Command::hasRemoveSignatory)
        .map(Command::getRemoveSignatory)
        .forEach(command -> {
          userQuorumProvider
              .setUserQuorumDetail(creatorAccountId,
                  userQuorumProvider.getUserQuorumDetail(creatorAccountId) - 1, syncTime);
          userQuorumProvider.setUserAccountQuorum(creatorAccountId,
              userQuorumProvider.getValidQuorumForUserAccount(creatorAccountId), syncTime);
        });
  }

  private void registerCreatedAccountByTransactionScanning(Transaction blockTransaction) {
    blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasCreateAccount)
        .map(Command::getCreateAccount)
        .filter(command -> userDomains.contains(command.getDomainId()))
        .forEach(command -> registrationProvider
            .register(String.format("%s@%s", command.getAccountName(), command.getDomainId()))
        );
  }

  private void tryToRemoveLock(Transaction transaction) {
    tryToRemoveLock(ValidationUtils.hexHash(transaction));
  }

  private void tryToRemoveLock(String hash) {
    String account = cacheProvider.getAccountBlockedBy(hash);
    if (account != null) {
      cacheProvider.unlockPendingAccount(account);
    }
  }

  private String getDomain(String accountId) {
    return accountId.split("@")[1];
  }

  @Override
  public void close() throws IOException {
    executorService.shutdownNow();
    irohaReliableChainListener.close();
  }
}
