package org.yilena.luna.rag.rankers;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 证据去重器，按来源与归一化内容消除重复证据。 */
@Component
public class EvidenceDeduplicator {

    public List<Evidence> deduplicate(List<Evidence> evidences) { // 定义方法签名
        if (evidences == null || evidences.isEmpty()) { // 进行条件判断
            return List.of(); // 返回处理结果
        } // 结束当前代码块
        Set<String> seen = new HashSet<>(); // 执行赋值操作
        List<Evidence> result = new ArrayList<>(); // 执行赋值操作
        for (Evidence evidence : evidences) { // 执行循环处理
            String key = evidence.getSource().value() + "::" + normalize(evidence.getContent()); // 执行赋值操作
            if (seen.add(key)) { // 进行条件判断
                result.add(evidence); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块
        return result; // 返回处理结果
    } // 结束当前代码块

    private String normalize(String content) { // 定义方法签名
        if (content == null) { // 进行条件判断
            return ""; // 返回处理结果
        } // 结束当前代码块
        String cleaned = content.replaceAll("\\s+", " ").trim().toLowerCase(); // 执行赋值操作
        if (cleaned.length() > 240) { // 进行条件判断
            return cleaned.substring(0, 240); // 返回处理结果
        } // 结束当前代码块
        return cleaned; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
