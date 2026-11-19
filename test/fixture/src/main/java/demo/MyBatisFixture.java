package demo;

import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Builds a plain (non-Spring) MyBatis factory and parses the mapper XML. */
public final class MyBatisFixture implements AutoCloseable {

    private final SqlSessionFactory factory;

    public MyBatisFixture() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:fixture", "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ValueMapper.class);
        try (InputStream xml = MyBatisFixture.class.getResourceAsStream("/mapper/ValueMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/ValueMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        this.factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    public String value() {
        try (SqlSession session = factory.openSession()) {
            return session.selectOne("demo.ValueMapper.value");
        }
    }

    @Override
    public void close() {
        // nothing to close; the factory is in-memory
    }
}
