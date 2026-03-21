package com.aiolos.plaza.order.chain;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ChainExecutor {
    
    public <T> void execute(List<ChainHandler<T>> handlers, T context) {
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        
        Chain<T> defaultChain = new Chain<T>() {
            private int index = 0;

            @Override
            public void proceed(T ctx) {
                if (index < handlers.size()) {
                    ChainHandler<T> handler = handlers.get(index++);
                    handler.handle(ctx, this);
                }
            }
        };
        
        // 开始执行第一个节点
        defaultChain.proceed(context);
    }
}