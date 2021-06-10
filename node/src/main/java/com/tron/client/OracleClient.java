package com.tron.client;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.tron.client.message.BroadCastResponse;
import com.tron.client.message.EventData;
import com.tron.client.message.EventResponse;
import com.tron.client.message.TriggerResponse;
import com.tron.common.AbiUtil;
import com.tron.common.Config;
import com.tron.common.util.HttpUtil;
import com.tron.common.util.Tool;
import com.tron.job.JobSubscriber;
import com.tron.keystore.KeyStore;
import com.tron.web.entity.Head;
import com.tron.web.entity.TronTx;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.tron.web.service.HeadService;
import com.tron.web.service.JobRunsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.spongycastle.util.encoders.Hex;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.JsonUtil;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.BadItemException;
import org.tron.protos.Protocol;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tron.common.Constant.*;

/** Subscribe the events of the oracle contracts and reply. */
@Slf4j
public class OracleClient {

  @Autowired
  private HeadService headService;
  @Autowired
  private JobRunsService jobRunsService;

  public OracleClient(HeadService _headService, JobRunsService _jobRunsService) {
    headService = _headService;
    jobRunsService = _jobRunsService;
  }

  public OracleClient() {
  }

  private static final String EVENT_NAME = "OracleRequest";
  private static final String EVENT_NEW_ROUND = "NewRound";
  private static final String VRF_EVENT_NAME = "VRFRequest";

  private static final HashMap<String, String> initiatorEventMap =
      new HashMap<String, String>() {
        {
          put(INITIATOR_TYPE_RUN_LOG, EVENT_NAME + "," + EVENT_NEW_ROUND); // support multiple events for the same job
          put(INITIATOR_TYPE_RANDOMNESS_LOG, VRF_EVENT_NAME);
        }
      };

  private static ConcurrentHashMap<String, HashMap<String, String>> listeningAddrs = new ConcurrentHashMap<>();
  private static Cache<String, String> requestIdsCache =
      CacheBuilder.newBuilder()
          .maximumSize(10000)
          .expireAfterWrite(12, TimeUnit.HOURS)
          .recordStats()
          .build();

  private HashMap<String, Long> consumeIndexMap = Maps.newHashMap();

  private ScheduledExecutorService listenExecutor = Executors.newSingleThreadScheduledExecutor();

  public void run() {
    listenExecutor.scheduleWithFixedDelay(
        () -> {
          try {
            listen();
          } catch (Throwable t) {
            log.error("Exception in listener ", t);
          }
        },
        0,
        3000,
        TimeUnit.MILLISECONDS);
  }

  public static void registerJob(String address, String jobId, String initiatorType) {
    HashMap<String, String> map = listeningAddrs.get(address);
    if (map == null) {
      map = new HashMap<String, String>();
    }
    map.put(jobId, initiatorEventMap.get(initiatorType));
    listeningAddrs.put(address, map);
  }

  /**
   * @param request
   * @return transactionid
   */
  public static TronTx fulfil(FulfillRequest request) throws Exception {
    Map<String, Object> params = Maps.newHashMap();
    params.put("owner_address", KeyStore.getAddr());
    params.put("contract_address", request.getContractAddr());
    params.put("function_selector", FULFIL_METHOD_SIGN);
    params.put("parameter", AbiUtil.parseParameters(FULFIL_METHOD_SIGN, request.toList()));
    params.put("fee_limit", Config.getMinFeeLimit());
    params.put("call_value", 0);
    params.put("visible", true);

    return triggerSignAndResponse(params);
  }

  /**
   * @param request
   * @return transactionid
   */
  public static TronTx vrfFulfil(FulfillRequest request) throws Exception {
    List<Object> parameters = Arrays.asList(request.getData());
    Map<String, Object> params = Maps.newHashMap();
    params.put("owner_address", KeyStore.getAddr());
    params.put("contract_address", request.getContractAddr());
    params.put("function_selector", VRF_FULFIL_METHOD_SIGN);
    params.put("parameter", AbiUtil.parseParameters(VRF_FULFIL_METHOD_SIGN, parameters));
    params.put("fee_limit", Config.getMinFeeLimit());
    params.put("call_value", 0);
    params.put("visible", true);

    return triggerSignAndResponse(params);
  }

  public static String convertWithIteration(Map<String, Object> map) {
    StringBuilder mapAsString = new StringBuilder("");
    for (String key : map.keySet()) {
      mapAsString.append(key + "=" + map.get(key) + ",");
    }
    mapAsString.delete(mapAsString.length()-1, mapAsString.length()).append("");
    return mapAsString.toString();
  }

  public static TronTx triggerSignAndResponse(Map<String, Object> params)
      throws Exception {
    String response =
        HttpUtil.post("https", FULLNODE_HOST, "/wallet/triggersmartcontract", params);
    TriggerResponse triggerResponse = null;
    triggerResponse = JsonUtil.json2Obj(response, TriggerResponse.class);

    // sign
    ECKey key = KeyStore.getKey();
    String rawDataHex = triggerResponse.getTransaction().getRawDataHex();
    Protocol.Transaction.raw raw =
        Protocol.Transaction.raw.parseFrom(ByteArray.fromHexString(rawDataHex));
    byte[] hash = Sha256Hash.hash(true, raw.toByteArray());
    ECKey.ECDSASignature signature = key.sign(hash);
    ByteString bsSign = ByteString.copyFrom(signature.toByteArray());
    TransactionCapsule transactionCapsule = new TransactionCapsule(raw, Arrays.asList(bsSign));

    String contractAddress = params.get("contract_address").toString();
    String data = convertWithIteration(params);

    // broadcast
    params.clear();
    params.put("transaction", Hex.toHexString(transactionCapsule.getInstance().toByteArray()));
    response = HttpUtil.post("https", FULLNODE_HOST,
            "/wallet/broadcasthex", params);
    BroadCastResponse broadCastResponse =
            JsonUtil.json2Obj(response, BroadCastResponse.class);

    TronTx tx = new TronTx();
    tx.setFrom(KeyStore.getAddr());
    tx.setTo(contractAddress);
    tx.setSurrogateId(broadCastResponse.getTxid());
    tx.setSignedRawTx(bsSign.toString());
    tx.setHash(ByteArray.toHexString(hash));
    tx.setData(data);
    return tx;
  }

  private void listen() {
    for (Map.Entry<String, HashMap<String, String>> addrEntry : listeningAddrs.entrySet()) {
      String addr = addrEntry.getKey();
      Map<String, String> map = addrEntry.getValue();
      Map.Entry<String, String> jobEventEntry = map.entrySet().iterator().next();
      String destJobId = jobEventEntry.getKey();
      String expectedEvents = jobEventEntry.getValue();
      String[] filterEvents = expectedEvents.split(",");
      List<EventData> events = new ArrayList<>();
      for (String filterEvent : filterEvents) {
        List<EventData> data = getEventData(addr, filterEvent);
        if (data != null && data.size() >0 ) {
          events.addAll(data);
        }
      }
      if (events == null || events.size() == 0) {
        continue;
      }
      // handle events
      for (EventData eventData : events) {
        // update consumeIndexMap
        updateConsumeMap(addr, eventData.getBlockTimestamp());

        // filter the events
        String eventName = eventData.getEventName();
        if (!EVENT_NAME.equals(eventName) && !EVENT_NEW_ROUND.equals(eventName) && !VRF_EVENT_NAME.equals(eventName)) {
          log.warn(
              "this node does not support this event, event name: {}", eventData.getEventName());
          continue;
        }
        switch (eventName) {
          case EVENT_NAME:
            processOracleRequestEvent(destJobId, addr, eventData);
            break;
          case EVENT_NEW_ROUND:
            processNewRoundEvent(addr, eventData);
            break;
          case VRF_EVENT_NAME:
            processVrfRequestEvent(destJobId, addr, eventData);
            break;
          default:
            log.warn("unexpected event:{}", eventName);
            break;
        }
      }
    }
  }

  /** constructor. */
  public static String getBlockByNum(long blockNum) {
    try {
      Map<String, Object> params = Maps.newHashMap();
      params.put("num", blockNum);
      params.put("visible", true);
      String response =
          HttpUtil.post("https", FULLNODE_HOST, "/wallet/getblockbynum", params);

      return response;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private void processOracleRequestEvent(String destJobId, String addr, EventData eventData) {
    String jobId = null;
    try {
      jobId = new String(
          org.apache.commons.codec.binary.Hex.decodeHex(
              ((String)eventData.getResult().get("specId"))));
    } catch (DecoderException e) {
      log.warn("parse job failed, jobid: {}", jobId);
      return;
    }
    // match jobId
    if (Strings.isNullOrEmpty(destJobId) || !destJobId.equals(jobId)) {
      log.warn("this node does not support this job, jobid: {}", jobId);
      return;
    }
    // Number/height of the block in which this request appeared
    long blockNum = eventData.getBlockNumber();
    String requester = Tool.convertHexToTronAddr((String)eventData.getResult().get("requester"));
    String callbackAddr = Tool.convertHexToTronAddr((String)eventData.getResult().get("callbackAddr"));
    String callbackFuncId = (String)eventData.getResult().get("callbackFunctionId");
    long cancelExpiration = Long.parseLong((String)eventData.getResult().get("cancelExpiration"));
    String data = (String)eventData.getResult().get("data");
    long dataVersion = Long.parseLong((String)eventData.getResult().get("dataVersion"));
    String requestId = (String)eventData.getResult().get("requestId");
    BigInteger payment = new BigInteger((String)eventData.getResult().get("payment"));
    if (requestIdsCache.getIfPresent(requestId) != null) {
      log.info("this event has been handled, requestid:{}", requestId);
      return;
    }
    JobSubscriber.receiveLogRequest(
        new EventRequest(blockNum, jobId, requester, callbackAddr, callbackFuncId,
            cancelExpiration, data, dataVersion,requestId, payment, addr));
    requestIdsCache.put(requestId, "");
  }

  private void processVrfRequestEvent(String destJobId, String addr, EventData eventData) {
    String jobId = null;
    try {
      jobId = new String(
          org.apache.commons.codec.binary.Hex.decodeHex(
              ((String)eventData.getResult().get("jobID"))));
    } catch (DecoderException e) {
      log.warn("parse vrf job failed, jobid: {}", jobId);
      return;
    }
    // match jobId
    if (Strings.isNullOrEmpty(destJobId) || !destJobId.equals(jobId)) {
      log.warn("this node does not support this vrf job, jobid: {}", jobId);
      return;
    }
    // Number/height of the block in which this request appeared
    long blockNum = eventData.getBlockNumber();
    String requestId = (String) eventData.getResult().get("requestID");
    if (requestIdsCache.getIfPresent(requestId) != null) {
      log.info("this vrf event has been handled, requestid:{}", requestId);
      return;
    }
    if(!Strings.isNullOrEmpty(jobRunsService.getByRequestId(requestId))) { // for reboot
      log.info("from DB, this vrf event has been handled, requestid:{}", requestId);
      return;
    }
    // Hash of the block in which this request appeared
    String responseStr = getBlockByNum(blockNum);
    JSONObject responseContent = JSONObject.parseObject(responseStr);
    String blockHash = responseContent.getString("blockID");
    JSONObject rawHead = JSONObject.parseObject(JSONObject.parseObject(responseContent.getString("block_header"))
        .getString("raw_data"));
    String parentHash = rawHead.getString("parentHash");
    Long blockTimestamp = Long.valueOf(rawHead.getString("timestamp"));

    String sender = Tool.convertHexToTronAddr((String) eventData.getResult().get("sender"));
    String keyHash = (String) eventData.getResult().get("keyHash");
    String seed = (String) eventData.getResult().get("seed");
    BigInteger fee = new BigInteger((String) eventData.getResult().get("fee"));
    JobSubscriber.receiveVrfRequest(
        new VrfEventRequest(
            blockNum, blockHash, jobId, keyHash, seed, sender, requestId, fee, addr));
    requestIdsCache.put(requestId, "");

    List<Head> hisHead = headService.getByAddress(addr);
    Head head = new Head();
    head.setAddress(addr);
    head.setNumber(blockNum);
    head.setHash(blockHash);
    head.setParentHash(parentHash);
    head.setBlockTimestamp(blockTimestamp);
    if (hisHead == null || hisHead.size() == 0) {
      headService.insert(head);
    } else if (!hisHead.get(0).getNumber().equals(blockNum)) { //Only update unequal blockNum.
      head.setId(hisHead.get(0).getId());
      head.setUpdatedAt(new Date());
      headService.update(head);
    } else {

    }
  }

  private void processNewRoundEvent(String addr, EventData eventData) {
    long roundId = 0;
    try {
      roundId = Long.parseLong((String)eventData.getResult().get("roundId"));
    } catch (NumberFormatException e) {
      log.warn("parse job failed, roundId: {}", roundId);
      return;
    }

    String startedBy = Tool.convertHexToTronAddr((String)eventData.getResult().get("startedBy"));
    long startedAt = Long.parseLong((String)eventData.getResult().get("startedAt"));
    if (requestIdsCache.getIfPresent(addr + roundId) != null) {
      log.info("this event has been handled, address:{}, roundId:{}", addr, roundId);
      return;
    }

    JobSubscriber.receiveNewRoundLog(addr, startedBy, roundId, startedAt);

    requestIdsCache.put(addr + roundId, "");
  }

  public String requestEvent(String urlPath, Map<String, String> params) throws IOException {
    String response = HttpUtil.get("https", HTTP_EVENT_HOST,
        urlPath, params);
    if (Strings.isNullOrEmpty(response)) {
      int retry = 1;
      for (;;) {
        if(retry > HTTP_MAX_RETRY_TIME) {
          break;
        }
        try {
          Thread.sleep(100 * retry);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        response = HttpUtil.get("https", HTTP_EVENT_HOST,
            urlPath, params);
        retry++;
        if (!Strings.isNullOrEmpty(response)) {
          break;
        }
      }
    }
    return response;
  }

  private List<EventData> getEventData(String addr, String filterEvent) {
    List<EventData> data = new ArrayList<>();
    String httpResponse = null;
    String urlPath;
    Map<String, String> params = Maps.newHashMap();
    if ("nile.trongrid.io".equals(HTTP_EVENT_HOST)) { // for test
      params.put("event_name", filterEvent);
      params.put("order_by", "block_timestamp,asc");
      if(!getMinBlockTimestamp(addr, filterEvent, params)){
        return null;
      }
      urlPath = String.format("/v1/contracts/%s/events", addr);
    } else { // for production
      params.put("event_name", filterEvent);
      params.put("order_by", "block_timestamp,asc");
      // params.put("only_confirmed", "true");
      if(!getMinBlockTimestamp(addr, filterEvent, params)){
        return null;
      }
      urlPath = String.format("/v1/contracts/%s/events", addr);
    }
    EventResponse response = null;
    try {
      httpResponse = requestEvent(urlPath, params);
      if (Strings.isNullOrEmpty(httpResponse)) {
        return null;
      }
      response = JsonUtil.json2Obj(httpResponse, EventResponse.class);
    } catch (IOException e) {
      log.error("parse response failed, err: {}", e.getMessage());
    }
    data.addAll(response.getData());

    boolean isNext = false;
    Map<String, String> links = response.getMeta().getLinks();
    if (links == null) {
      return data;
    }
    String urlNext = links.get("next");
    if (!Strings.isNullOrEmpty(urlNext)) {
      isNext = true;
    }
    while (isNext) {
      isNext = false;
      String responseNext = requestNextPage(urlNext);
      if (Strings.isNullOrEmpty(responseNext)) {
        return data;
      }
      try {
        response = JsonUtil.json2Obj(responseNext, EventResponse.class);
      } catch (Exception e) {
        log.error("parse response failed, err: {}", e.getMessage());
      }
      data.addAll(response.getData());

      links = response.getMeta().getLinks();
      if (links == null) {
        return data;
      }
      urlNext = links.get("next");
      if (!Strings.isNullOrEmpty(urlNext)) {
        isNext = true;
      }
    }

    return data;
  }
  public String requestNextPage(String urlNext) {
    try {
      String response = HttpUtil.requestWithRetry(urlNext);
      if (response == null) {
        return null;
      }
      return response;
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return null;
  }



  private void updateConsumeMap(String addr, long timestamp) {
    if (consumeIndexMap.containsKey(addr)) {
      if (timestamp > consumeIndexMap.get(addr)) {
        consumeIndexMap.put(addr, timestamp);
      }
    } else {
      consumeIndexMap.put(addr, timestamp);
    }
  }

  public boolean getMinBlockTimestamp(String addr, String eventName, Map<String, String> params)
  {
    switch (eventName) {
      case EVENT_NAME:
        if (consumeIndexMap.containsKey(addr)) {
          params.put("min_block_timestamp", Long.toString(consumeIndexMap.get(addr)));
        } else {
          params.put("min_block_timestamp", Long.toString(System.currentTimeMillis() - ONE_MINUTE));
        }
        break;
      case VRF_EVENT_NAME:
        if (consumeIndexMap.containsKey(addr)) {
          params.put("min_block_timestamp", Long.toString(consumeIndexMap.get(addr)));
        } else {
          List<Head> hisHead = headService.getByAddress(addr);
          if (hisHead == null || hisHead.size() == 0) {
            params.put("min_block_timestamp", Long.toString(System.currentTimeMillis() - ONE_MINUTE));
          } else {
            params.put("min_block_timestamp", Long.toString(hisHead.get(0).getBlockTimestamp()));
          }
        }
        break;
      default:
        log.warn("unexpected event:{}", eventName);
        return false;
    }
    return true;
  }
}
