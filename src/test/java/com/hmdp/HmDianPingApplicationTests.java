package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Autowired
    private CacheClient cacheClient;

    @Test
    void testSaveShop() throws InterruptedException {
        //shopService.saveShop2Redis(1L, 10L);
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogical(RedisConstants.CACHE_SHOP_KEY + 1L, shop, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
    }

}
