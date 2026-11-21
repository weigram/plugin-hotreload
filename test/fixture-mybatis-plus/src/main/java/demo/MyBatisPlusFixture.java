package demo;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

/**
 * Minimal MyBatis-Plus application. BaseMapper injects selectList into the
 * configuration; the XML contributes a resultMap and one statement. This is
 * the setup that reproduced "Mapped Statements collection does not contain
 * value for ...selectList" after an XML reload.
 */
public final class MyBatisPlusFixture implements AutoCloseable {

    private final SqlSession session;
    private final DemoMapper mapper;

    public MyBatisPlusFixture() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:mpfixture;DB_CLOSE_DELAY=-1", "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream xml = MyBatisPlusFixture.class.getResourceAsStream("/mapper/DemoMapper.xml")) {
            new MybatisXMLMapperBuilder(xml, configuration, "mapper/DemoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        this.session = factory.openSession(true);
        this.mapper = session.getMapper(DemoMapper.class);
        init();
    }

    private void init() throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table demo (id bigint primary key, name varchar(64))");
            statement.execute("insert into demo values (1, 'alpha')");
        }
    }

    /** Both calls are printed on one line so the test can assert either half. */
    public String state() {
        String points;
        try {
            List<DemoEntity> rows = mapper.selectList(null);
            points = "mp=" + rows.size();
            if (!rows.isEmpty()) {
                points += "=" + rows.get(0).getName();
            }
        } catch (Throwable failure) {
            points = "mp=ERROR:" + failure.getClass().getSimpleName();
        }

        String xml;
        try {
            xml = mapper.xmlValue();
        } catch (Throwable failure) {
            xml = "MISSING";
        }
        return points + " xml=" + xml;
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
