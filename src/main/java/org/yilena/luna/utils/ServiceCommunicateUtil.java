package org.yilena.luna.utils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 该工具类用于维护服务间共享的简单信号量映射，便于不同模块通过符号值传递轻量状态。
 */
public class ServiceCommunicateUtil {

    /**
     * 保存服务通信符号及其数值的并发映射表。
     */
    public volatile static ConcurrentHashMap<String, Integer> SymbolMap = new ConcurrentHashMap<>();

    /**
     * 新增一个服务通信符号。
     */
    public static void addSymbol(String symbol, int val) {
        SymbolMap.put(symbol, val);
    }

    /**
     * 获取指定服务通信符号的当前值。
     */
    public static int getSymbol(String symbol) {
        return SymbolMap.getOrDefault(symbol, 0);
    }

    /**
     * 更新指定服务通信符号的值。
     */
    public static void updateSymbol(String symbol, int val) {
        SymbolMap.put(symbol, val);
    }

    /**
     * 删除指定服务通信符号。
     */
    public static void removeSymbol(String symbol) {
        SymbolMap.remove(symbol);
    }
}
