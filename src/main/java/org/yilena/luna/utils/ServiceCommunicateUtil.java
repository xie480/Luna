package org.yilena.luna.utils; // define package

import java.util.concurrent.ConcurrentHashMap; // import dependency

/*
    服务间通信工具类 // business logic
 */
public class ServiceCommunicateUtil { // define class
    public volatile static ConcurrentHashMap<String, Integer> SymbolMap = new ConcurrentHashMap<>(); // method signature

    public static void addSymbol(String symbol, int val) { // method definition
        SymbolMap.put(symbol, val); // business logic
    } // block end

    public static int getSymbol(String symbol) { // method definition
        return SymbolMap.getOrDefault(symbol, 0); // return result
    } // block end

    public static void updateSymbol(String symbol, int val) { // method definition
        SymbolMap.put(symbol, val); // business logic
    } // block end

    public static void removeSymbol(String symbol) { // method definition
        SymbolMap.remove(symbol); // business logic
    } // block end
} // block end
