package demo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * MyBatis-Plus mapper. {@code selectList} is injected by MyBatis-Plus at
 * startup and has no XML statement; {@code xmlValue} comes from the XML.
 */
public interface DemoMapper extends BaseMapper<DemoEntity> {

    String xmlValue();
}
