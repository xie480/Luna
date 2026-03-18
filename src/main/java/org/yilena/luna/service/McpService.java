package org.yilena.luna.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.yilena.luna.entity.Resource;

import java.util.List;

public interface McpService extends IService<Resource> {
    /**
     * 註冊資源
     */
    Resource registerResource(Resource resource);

    /**
     * 搜索資源
     */
    List<Resource> searchResources(String query);
}
