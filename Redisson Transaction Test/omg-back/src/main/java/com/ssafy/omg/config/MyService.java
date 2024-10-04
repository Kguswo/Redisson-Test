package com.ssafy.omg.config;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MyService {

    private final RedissonClient redissonClient;

    @Autowired
    public MyService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public void executeTaskWithLock() {
        RLock lock = redissonClient.getLock("myLock");
        try {
            // 락을 시도합니다.
            lock.lock();

            // 작업 수행
            System.out.println("작업 수행 중...");
        } finally {
            // 항상 락을 해제해야 합니다.
            lock.unlock();
        }
    }
}
