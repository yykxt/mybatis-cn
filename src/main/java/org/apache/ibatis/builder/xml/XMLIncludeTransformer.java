/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 * inlcude 解析相关类
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  /**
   * 从 parseStatementNode 方法进入时， Node 还是 （select|insert|update|delete） 节点
   */
  public void applyIncludes(Node source) {
    Properties variablesContext = new Properties();
    // 获取的是 mybatis-config.xml 所定义的属性
    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      variablesContext.putAll(configurationVariables);
    }
    // 处理 <include> 子节点
    applyIncludes(source, variablesContext, false);
  }

  /**
   * Recursively apply includes through all SQL fragments.
   * 递归的包含所有的 SQL 节点
   *
   * @param source           Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    // 下面是处理 include 子节点
    if (source.getNodeName().equals("include")) {
      // 查找 refid 属性指向 <sql> 节点
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      // 解析 <include> 节点下的 <property> 节点， 将得到的键值对添加到 variablesContext 中
      // 并形成 Properties 对象返回， 用于替换占位符
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      // 递归处理 <include> 节点， 在 <sql> 节点中可能会 <include> 其他 SQL 片段
      applyIncludes(toInclude, toIncludeContext, true);
      // 如果不是同一个文档（aMapper.xml 可能通过 refid 引用 bMapper.xml 的属性），
      // 则将 toinclude 节点的递归复制过来
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      // 将 <include> 节点替换成解析后的 <sql>
      // 这里有个很关键的点， <sql> 在此时的所属节点是文档本身
      source.getParentNode().replaceChild(toInclude, source);
      // 执行完上面的函数之后， <sql> 节点的所属节点是原来 <include>的节点
      while (toInclude.hasChildNodes()) {
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        //  获取所有的属性值， 并使用 variablesContext 进行占位符的解析
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      // 获取所有的子类， 并递归解析
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && source.getNodeType() == Node.TEXT_NODE
            && !variablesContext.isEmpty()) {
      // replace variables in text node
      // 使用 variablesContext 进行占位符的解析
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  /**
   * 通过 refid 查找 sql 节点
   */
  private Node findSqlFragment(String refid, Properties variables) {
    // refid 也可以是 ${xxx} 的形式， 因此需要使用 PropertyParser 进行解析
    refid = PropertyParser.parse(refid, variables);
    // 在 refid 前面添加 namespace
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      // 通过 Configuration.sqlFragments 获取该值
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition.
   * <p>
   * 从 include 下获取 properties 值， 并添加到 inheritedVariablesContext 中
   *
   * @param node                      Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;
    // 获取子节点
    NodeList children = node.getChildNodes();
    // 遍历
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        // properties 的 value 值还可以使用 ${xxxx} 的方式， 因此通过 PropertyParser.parse 进行解析
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<>();
        }
        // 将其存入 declaredProperties 中
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      // 如果是非空， 生产一个新的 Properties 对象， 并将上面获得取得 Map 对象放入其中
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
