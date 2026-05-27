package com.bhm.demo.entity

/**
 * 扫描结果列表过滤模式
 */
enum class ScanFilterMode {
    /** 显示所有设备 */
    ALL,
    /** 仅显示有广播名的设备 */
    NAMED_ONLY,
    /** 按名称关键字过滤（支持模糊匹配，也可匹配 MAC） */
    NAME_FILTER
}
