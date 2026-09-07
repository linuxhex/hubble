package com.ykc.hubble.vo;

import lombok.Data;

import java.util.List;

@Data
public class ErrorTrendVO {
    private List<Long> timestamps;
    private List<Series> series;

    @Data
    public static class Series {
        private String typeName;
        private List<Long> counts;
    }
}
