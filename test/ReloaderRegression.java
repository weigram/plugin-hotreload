import com.hotreload.agent.MyBatisXmlReloader;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.session.Configuration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Regression coverage for the two follow-ups in issue #1:
 *  - a deferred MyBatis parse error must keep the old statements and allow a retry;
 *  - a reload must target only the Configuration that loaded the changed resource.
 */
public final class ReloaderRegression {
    private static final String HEADER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" " +
            "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n";

    private ReloaderRegression() {
    }

    private static Configuration load(Path xml, String resource) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(xml)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(Configuration configuration) {
        return configuration.getMappedStatement("review.Mapper.value")
                .getSqlSource().getBoundSql(null).getSql().trim();
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, (HEADER + "<mapper namespace=\"review.Mapper\">\n" + body
                + "\n</mapper>\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void addInjected(Configuration configuration, String id) {
        MappedStatement statement = new MappedStatement.Builder(
                configuration, id, new StaticSqlSource(configuration, "SELECT 99"),
                SqlCommandType.SELECT)
                .resource("interface review.Mapper")
                .build();
        configuration.addMappedStatement(statement);
    }

    private static void check(String what, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected [" + expected + "] but got [" + actual + "]");
        }
        System.out.println("PASS " + what + " -> " + actual);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("reloader-regression");
        Path a = dir.resolve("a/ReviewMapper.xml");
        Path b = dir.resolve("b/ReviewMapper.xml");
        write(a, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 1</select>");
        write(b, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 20</select>");
        Configuration ca = load(a, "a/ReviewMapper.xml");
        Configuration cb = load(b, "b/ReviewMapper.xml");

        write(a, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 10</select>");
        MyBatisXmlReloader.reload(a, Arrays.asList(ca, cb));
        check("configuration A", sql(ca), "SELECT 10");
        check("configuration B remains untouched", sql(cb), "SELECT 20");

        Path bad = dir.resolve("review/ReviewMapper.xml");
        write(bad, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 1</select>");
        Configuration cc = load(bad, "review/ReviewMapper.xml");
        write(bad, "<select id=\"value\" resultMap=\"missingMap\">SELECT 3</select>");
        MyBatisXmlReloader.reload(bad, Arrays.asList(cc));
        check("deferred error keeps old statement", sql(cc), "SELECT 1");

        write(bad, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 4</select>");
        MyBatisXmlReloader.reload(bad, Arrays.asList(cc));
        check("corrected file reloads after failure", sql(cc), "SELECT 4");

        Path generated = dir.resolve("generated/ReviewMapper.xml");
        write(generated, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 1</select>");
        Configuration cg = load(generated, "generated/ReviewMapper.xml");
        addInjected(cg, "review.Mapper.selectList");
        write(generated, "<resultMap id=\"m\" type=\"java.util.HashMap\">"
                + "<result column=\"c\" property=\"x\"/></resultMap>");
        MyBatisXmlReloader.reload(generated, Arrays.asList(cg));
        check("injected BaseMapper statement survives result-map-only reload",
                cg.hasStatement("review.Mapper.selectList") ? "yes" : "no", "yes");
        check("removed XML statement is gone",
                cg.hasStatement("review.Mapper.value") ? "yes" : "no", "no");

        write(generated, "<select id=\"value\" resultType=\"java.lang.String\">SELECT 5</select>");
        MyBatisXmlReloader.reload(generated, Arrays.asList(cg));
        check("XML statement reloads next to injected statement", sql(cg), "SELECT 5");
        check("injected BaseMapper statement still present",
                cg.hasStatement("review.Mapper.selectList") ? "yes" : "no", "yes");
        System.out.println("RELOADER REGRESSION PASS");
    }
}
