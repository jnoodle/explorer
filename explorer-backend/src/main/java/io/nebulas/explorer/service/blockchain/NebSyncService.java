package io.nebulas.explorer.service.blockchain;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import io.nebulas.explorer.domain.BlockSyncRecord;
import io.nebulas.explorer.domain.NebAddress;
import io.nebulas.explorer.domain.NebBlock;
import io.nebulas.explorer.domain.NebDynasty;
import io.nebulas.explorer.domain.NebPendingTransaction;
import io.nebulas.explorer.domain.NebTransaction;
import io.nebulas.explorer.enums.NebAddressTypeEnum;
import io.nebulas.explorer.enums.NebTransactionTypeEnum;
import io.nebulas.explorer.grpc.GrpcChannelService;
import io.nebulas.explorer.service.thirdpart.nebulas.NebApiServiceWrapper;
import io.nebulas.explorer.service.thirdpart.nebulas.bean.Block;
import io.nebulas.explorer.service.thirdpart.nebulas.bean.GetAccountStateResponse;
import io.nebulas.explorer.service.thirdpart.nebulas.bean.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Desc:
 * User: nathan
 * Date: 2018-03-20
 */
@Slf4j(topic = "subscribe")
@Service
public class NebSyncService {

    @Autowired
    private GrpcChannelService grpcChannelService;
    @Autowired
    private NebBlockService nebBlockService;
    @Autowired
    private NebTransactionService nebTransactionService;
    @Autowired
    private NebAddressService nebAddressService;
    @Autowired
    private NebDynastyService nebDynastyService;
    @Autowired
    private NebApiServiceWrapper nebApiServiceWrapper;
    @Autowired
    private BlockSyncRecordService blockSyncRecordService;

    private static final Base64.Decoder DECODER = Base64.getDecoder();

    public void syncBlockByHash(String hash, boolean isLib) {
        try {
            Block block = nebApiServiceWrapper.getBlockByHash(hash, true);
            if (block == null) {
                log.error("block with hash {} not found", hash);
                return;
            }
            log.info("get block by hash {}", block.getHash());

            syncBlock(block, isLib);
        } catch (Exception e) {
            log.error("no block yet", e);
        }
    }

    public void syncBlockByHeight(long height, boolean isLib) {
        try {
            Block block = nebApiServiceWrapper.getBlockByHeight(height);
            if (block == null) {
                log.error("block with height {} not found", height);
                return;
            }
            syncBlock(block, isLib);
        } catch (Exception e) {
            log.error("no block yet", e);
        }
    }

    private void syncBlock(Block block, boolean isLib) {
        if (null == block) {
            return;
        }

        syncAddresses(Arrays.asList(block.getMiner(), block.getCoinbase()));

        NebBlock newBlock = NebBlock.builder()
                .height(block.getHeight())
                .hash(block.getHash())
                .parentHash(block.getParentHash())
                .timestamp(new Date(block.getTimestamp() * 1000))
                .miner(block.getMiner())
                .coinbase(block.getCoinbase())
                .finality(isLib)
                .createdAt(new Date(System.currentTimeMillis())).build();
        if (isLib) {
            nebBlockService.replaceNebBlock(newBlock);
        } else {
            nebBlockService.addNebBlock(newBlock);
        }

        //sync transaction
        List<Transaction> txs = block.getTransactions();
        if (isLib) {
            nebTransactionService.deleteNebTransactionByBlkHeight(block.getHeight());
        }
        int i = 0;
        long txSyncedCount = 0;
        for (Transaction tx : txs) {
            i++;
            try {
                syncTx(tx, block, i);
                txSyncedCount++;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }

        //sync dynasty
        List<String> dynastyList = nebApiServiceWrapper.getDynasty(block.getHeight());
        nebDynastyService.batchAddNebDynasty(block.getHeight(), dynastyList);

        //if isLib and synced all the txns successfully, then then block can be confirmed directly
        boolean canBeConfirmed = isLib && txSyncedCount == txs.size();
        if (canBeConfirmed) {
            blockSyncRecordService.setConfirmed(block.getHeight(), txSyncedCount);
        }
    }

    private void syncTx(Transaction tx, Block block, int seq) {
        //sync address
        syncAddress(tx.getFrom(), NebAddressTypeEnum.NORMAL);

        NebTransactionTypeEnum typeEnum = NebTransactionTypeEnum.parse(tx.getType());

        if (NebTransactionTypeEnum.BINARY.equals(typeEnum)) {
            syncAddress(tx.getTo(), NebAddressTypeEnum.NORMAL);
        } else if (NebTransactionTypeEnum.CALL.equals(typeEnum)) {
            syncAddress(tx.getTo(), NebAddressTypeEnum.CONTRACT);
            String realReceiver = extractReceiverAddress(tx.getData());
            syncAddress(realReceiver, NebAddressTypeEnum.NORMAL);
        } else if (NebTransactionTypeEnum.DEPLOY.equals(typeEnum)) {
            syncAddress(tx.getContractAddress(), NebAddressTypeEnum.CONTRACT);
        }

        NebPendingTransaction nebPendingTransaction = nebTransactionService.getNebPendingTransactionByHash(tx.getHash());
        if (nebPendingTransaction != null) {
            nebTransactionService.deleteNebPendingTransaction(tx.getHash());
        }

        NebTransaction nebTxs = NebTransaction.builder()
                .hash(tx.getHash())
                .blockHeight(block.getHeight())
                .blockHash(block.getHash())
                .txSeq(seq)
                .from(tx.getFrom())
                .to(tx.getTo())
                .status(tx.getStatus())
                .value(tx.getValue())
                .nonce(tx.getNonce())
                .timestamp(new Date(block.getTimestamp() * 1000))
                .type(tx.getType())
                .contractAddress(StringUtils.isEmpty(tx.getContractAddress()) ? "" : tx.getContractAddress())
                .data(block.getHeight() == 1 ? convertData(typeEnum, tx.getData()) : tx.getData())
                .gasPrice(tx.getGasPrice())
                .gasLimit(tx.getGasLimit())
                .gasUsed(tx.getGasUsed())
                .createdAt(new Date())
                .executeError(StringUtils.isEmpty(tx.getExecuteError()) ? "" : tx.getExecuteError())
                .build();
        nebTransactionService.addNebTransaction(nebTxs);
    }

    public void syncPendingTx(String hash) {
        if (StringUtils.isEmpty(hash)) {
            return;
        }

        NebPendingTransaction pendingNebTransaction = nebTransactionService.getNebPendingTransactionByHash(hash);
        if (pendingNebTransaction == null) {
            Transaction txSource = nebApiServiceWrapper.getTransactionReceipt(hash);

            if (txSource == null) {
                log.warn("pending tx with hash {} not ready", hash);
            } else {
                NebTransactionTypeEnum typeEnum = NebTransactionTypeEnum.parse(txSource.getType());

                syncAddress(txSource.getFrom(), NebAddressTypeEnum.NORMAL);

                if (NebTransactionTypeEnum.BINARY.equals(typeEnum)) {
                    syncAddress(txSource.getTo(), NebAddressTypeEnum.NORMAL);
                } else if (NebTransactionTypeEnum.CALL.equals(typeEnum)) {
                    syncAddress(txSource.getTo(), NebAddressTypeEnum.CONTRACT);
                    String realReceiver = extractReceiverAddress(txSource.getData());
                    syncAddress(realReceiver, NebAddressTypeEnum.NORMAL);
                } else if (NebTransactionTypeEnum.DEPLOY.equals(typeEnum)) {
                    syncAddress(txSource.getContractAddress(), NebAddressTypeEnum.CONTRACT);
                }

                log.info("get pending tx by hash {}", hash);
                NebPendingTransaction pendingTxToSave = NebPendingTransaction.builder()
                        .hash(hash)
                        .from(txSource.getFrom())
                        .to(txSource.getTo())
                        .value(txSource.getValue())
                        .nonce(txSource.getNonce())
                        .timestamp(new Date(txSource.getTimestamp() * 1000))
                        .type(txSource.getType())
                        .contractAddress(StringUtils.isEmpty(txSource.getContractAddress()) ? "" : txSource.getContractAddress())
                        .gasPrice(txSource.getGasPrice())
                        .gasLimit(txSource.getGasLimit())
                        .createdAt(new Date())
                        .data(txSource.getData())
                        .build();
                nebTransactionService.addNebPendingTransaction(pendingTxToSave);
            }
        } else {
            log.warn("duplicate pending neb transaction {}", pendingNebTransaction.getHash());
        }
    }

    public void deletePendingTx(String hash){
        if (StringUtils.isEmpty(hash)) {
            return;
        }
        nebTransactionService.deleteNebPendingTransaction(hash);
    }

    private void syncAddress(String hash, NebAddressTypeEnum type) {
        if (StringUtils.isEmpty(hash)) {
            return;
        }
        try {
            NebAddress addr = nebAddressService.getNebAddressByHash(hash);
            if (addr == null) {
                addr = nebAddressService.getNebAddressByHashRpc(hash);
                if (null != addr) {
                    nebAddressService.addNebAddress(addr);
                    syncBalance(hash);
                }
            }
        } catch (Throwable e) {
            log.error("add address error", e);
        }
    }

    private void syncAddresses(List<String> addresses) {
        if (CollectionUtils.isNotEmpty(addresses)) {
            for (String s : addresses) {
                syncAddress(s, NebAddressTypeEnum.NORMAL);
            }
        }
    }

    private String convertData(NebTransactionTypeEnum type, String data) {
        if (StringUtils.isEmpty(data)) {
            return "";
        }
        if (NebTransactionTypeEnum.BINARY.equals(type)) {
            try {
                return new String(DECODER.decode(data), "UTF-8");
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        return data;
    }

    private String extractReceiverAddress(String data) {
        try {
            String dataStr = new String(DECODER.decode(data), "UTF-8");
            JSONObject jsonObject = JSONObject.parseObject(dataStr);
            String func = jsonObject.getString("Function");

            if ("transfer".equals(func)) {
                JSONArray array = jsonObject.getJSONArray("Args");
                return array.getString(0);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "";
    }

    /**
     * sync the account balance
     * @param hash
     */
    public void syncBalance(String hash) {
        try {
            NebAddress address = nebAddressService.getNebAddressByHash(hash);
            if (null == address) {
                return;
            }
            if (address.getUpdatedAt().before(LocalDateTime.now().plusMinutes(-5).toDate())) {
                GetAccountStateResponse accountState = nebApiServiceWrapper.getAccountState(address.getHash());
                if (null != accountState && StringUtils.isNotEmpty(accountState.getBalance())) {
                    String balance = accountState.getBalance();
                    String nocne = accountState.getNonce();
                    address.setCurrentBalance(new BigDecimal(balance));
                    nebAddressService.updateAddressBalance(hash, balance, nocne);
                }
            }
        } catch (Exception e) {
            log.warn("sync account[{}] balance error", hash);
        }
    }
}
