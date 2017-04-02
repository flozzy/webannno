/*
 * Copyright 2015
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.constraints.model;

import java.io.Serializable;
import java.util.List;

/**
 * Class representating "Scope" (name) and list of "Rules" for a particular "Scope".
 */
public class Scope
    implements Serializable
{
    private static final long serialVersionUID = 226908916809455385L;
    private final String scopeName;
    private final List<Rule> rules;

    public Scope(String scopeName, List<Rule> rules)
    {
        this.scopeName = scopeName;
        this.rules = rules;
    }

    public String getScopeName()
    {
        return scopeName;
    }

    public List<Rule> getRules()
    {
        return rules;
    }

    @Override
    public String toString()
    {
        return "Scope [" + scopeName + "]\nRules\n" + rules.toString() + "]";
    }

}
