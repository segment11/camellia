package com.netease.nim.camellia.redis.proxy.upstream.kv.command.zset;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.monitor.KvCacheMonitor;
import com.netease.nim.camellia.redis.proxy.reply.ErrorReply;
import com.netease.nim.camellia.redis.proxy.reply.MultiBulkReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.upstream.kv.cache.ZSet;
import com.netease.nim.camellia.redis.proxy.upstream.kv.cache.ZSetLRUCache;
import com.netease.nim.camellia.redis.proxy.upstream.kv.command.CommanderConfig;
import com.netease.nim.camellia.redis.proxy.upstream.kv.kv.KeyValue;
import com.netease.nim.camellia.redis.proxy.upstream.kv.kv.Sort;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.EncodeVersion;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.KeyMeta;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.KeyType;
import com.netease.nim.camellia.redis.proxy.upstream.kv.utils.BytesUtils;
import com.netease.nim.camellia.tools.utils.BytesKey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ZRANGEBYSCORE key min max [WITHSCORES] [LIMIT offset count]
 * <p>
 * Created by caojiajun on 2024/4/11
 */
public class ZRangeByScoreCommander extends ZRange0Commander {

    private static final byte[] script = ("local ret1 = redis.call('exists', KEYS[1]);\n" +
            "if tonumber(ret1) == 1 then\n" +
            "  local ret = redis.call('zrangebyscore', KEYS[1], unpack(ARGV));\n" +
            "  return {'1', ret};\n" +
            "end\n" +
            "return {'2'};").getBytes(StandardCharsets.UTF_8);

    public ZRangeByScoreCommander(CommanderConfig commanderConfig) {
        super(commanderConfig);
    }

    @Override
    public RedisCommand redisCommand() {
        return RedisCommand.ZRANGEBYSCORE;
    }

    @Override
    protected boolean parse(Command command) {
        byte[][] objects = command.getObjects();
        return objects.length >= 4;
    }

    @Override
    protected Reply execute(Command command) {
        byte[][] objects = command.getObjects();
        byte[] key = objects[1];
        KeyMeta keyMeta = keyMetaServer.getKeyMeta(key);
        if (keyMeta == null) {
            return MultiBulkReply.EMPTY;
        }
        if (keyMeta.getKeyType() != KeyType.zset) {
            return ErrorReply.WRONG_TYPE;
        }
        boolean withScores = ZSetWithScoresUtils.isWithScores(objects, 4);

        ZSetScore minScore;
        ZSetScore maxScore;
        ZSetLimit limit;
        try {
            minScore = ZSetScore.fromBytes(objects[2]);
            maxScore = ZSetScore.fromBytes(objects[3]);
            limit = ZSetLimit.fromBytes(objects, 4);
        } catch (Exception e) {
            return ErrorReply.SYNTAX_ERROR;
        }
        if (minScore.getScore() > maxScore.getScore()) {
            return MultiBulkReply.EMPTY;
        }

        byte[] cacheKey = keyDesign.cacheKey(keyMeta, key);
        if (cacheConfig.isZSetLocalCacheEnable()) {
            ZSetLRUCache zSetLRUCache = cacheConfig.getZSetLRUCache();

            boolean hotKey = zSetLRUCache.isHotKey(key);

            List<ZSetTuple> list = zSetLRUCache.zrangebyscore(cacheKey, minScore, maxScore, limit);
            if (list != null) {
                KvCacheMonitor.localCache(cacheConfig.getNamespace(), redisCommand().strRaw());
                return ZSetTupleUtils.toReply(list, withScores);
            }

            if (hotKey) {
                ZSet zSet = loadLRUCache(keyMeta, key);
                if (zSet != null) {
                    //
                    zSetLRUCache.putZSet(cacheKey, zSet);
                    //
                    list = zSet.zrangebyscore(minScore, maxScore, limit);
                    KvCacheMonitor.localCache(cacheConfig.getNamespace(), redisCommand().strRaw());
                    return ZSetTupleUtils.toReply(list, withScores);
                }
            }
        }

        EncodeVersion encodeVersion = keyMeta.getEncodeVersion();
        if (encodeVersion == EncodeVersion.version_0) {
            KvCacheMonitor.kvStore(cacheConfig.getNamespace(), redisCommand().strRaw());
            return zrangeByScoreVersion0(keyMeta, key, minScore, maxScore, limit, withScores);
        }

        if (encodeVersion == EncodeVersion.version_3) {
            return zrangeVersion3(keyMeta, key, cacheKey, objects, withScores);
        }

        byte[][] args = new byte[objects.length - 2][];
        System.arraycopy(objects, 2, args, 0, args.length);

        if (encodeVersion == EncodeVersion.version_1) {
            return zrangeVersion1(keyMeta, key, cacheKey, args, script, true);
        }
        if (encodeVersion == EncodeVersion.version_2) {
            return zrangeVersion2(keyMeta, key, cacheKey, args, withScores, script, true);
        }
        return ErrorReply.INTERNAL_ERROR;
    }

    private Reply zrangeByScoreVersion0(KeyMeta keyMeta, byte[] key, ZSetScore minScore, ZSetScore maxScore, ZSetLimit limit, boolean withScores) {
        byte[] startKey = keyDesign.zsetMemberSubKey2(keyMeta, key, new byte[0], BytesUtils.toBytes(minScore.getScore()));
        byte[] endKey = BytesUtils.nextBytes(keyDesign.zsetMemberSubKey2(keyMeta, key, new byte[0], BytesUtils.toBytes(maxScore.getScore())));
        int batch = kvConfig.scanBatch();
        int count = 0;
        List<ZSetTuple> result = new ArrayList<>(limit.getCount() < 0 ? 16 : Math.min(limit.getCount(), 100));
        while (true) {
            if (limit.getCount() > 0) {
                batch = Math.min(kvConfig.scanBatch(), limit.getCount() - result.size());
            }
            List<KeyValue> list = kvClient.scanByStartEnd(startKey, endKey, batch, Sort.ASC, false);
            if (list.isEmpty()) {
                break;
            }
            for (KeyValue keyValue : list) {
                if (keyValue == null) {
                    continue;
                }
                startKey = keyValue.getKey();
                if (keyValue.getValue() == null) {
                    continue;
                }
                double score = keyDesign.decodeZSetScoreBySubKey2(keyValue.getKey(), key);
                boolean pass = ZSetScoreUtils.checkScore(score, minScore, maxScore);
                if (!pass) {
                    continue;
                }
                if (count >= limit.getOffset()) {
                    byte[] member = keyDesign.decodeZSetMemberBySubKey2(keyValue.getKey(), key);
                    ZSetTuple tuple;
                    if (withScores) {
                        tuple = new ZSetTuple(new BytesKey(member), score);
                    } else {
                        tuple = new ZSetTuple(new BytesKey(member), null);
                    }
                    result.add(tuple);
                    if (limit.getCount() > 0 && result.size() >= limit.getCount()) {
                        break;
                    }
                }
                count ++;
            }
            if (list.size() < batch) {
                break;
            }
        }
        return ZSetTupleUtils.toReply(result, withScores);
    }

}
