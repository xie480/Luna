package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.service.KnowledgeBaseService;

/**
 * 知識庫服務實現類
 */
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements KnowledgeBaseService {
}
