package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.mapper.ResourceMapper;
import org.yilena.luna.service.McpService;

import java.util.List;

@Service
public class McpServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements McpService {

    @Override
    public Resource registerResource(Resource resource) {
        this.saveOrUpdate(resource);
        return resource;
    }

    @Override
    public List<Resource> searchResources(String query) {
        LambdaQueryWrapper<Resource> wrapper = new LambdaQueryWrapper<>();
        if (query != null && !query.isBlank()) {
            wrapper.like(Resource::getName, query)
                    .or()
                    .like(Resource::getDescription, query);
        }
        return this.list(wrapper);
    }
}
