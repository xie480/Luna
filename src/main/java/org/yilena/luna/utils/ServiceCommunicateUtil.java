package org.yilena.luna.utils;

import java.util.concurrent.ConcurrentHashMap;

/*
    服务间通信工具类
 */
public class ServiceCommunicateUtil {
    public volatile static ConcurrentHashMap<String, Integer> SymbolMap = new ConcurrentHashMap<>();

    public static void addSymbol(String symbol, int val) {
        SymbolMap.put(symbol, val);
    }

    public static int getSymbol(String symbol) {
        return SymbolMap.getOrDefault(symbol, 0);
    }

    public static void updateSymbol(String symbol, int val) {
        SymbolMap.put(symbol, val);
    }

    public static void removeSymbol(String symbol) {
        SymbolMap.remove(symbol);
    }
}
