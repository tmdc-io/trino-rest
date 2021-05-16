/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pl.net.was.rest.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

import static java.util.Objects.requireNonNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactsList
        implements Envelope<Artifact>
{
    private final long totalCount;
    private final List<Artifact> artifacts;

    public ArtifactsList(
            @JsonProperty("total_count") long totalCount,
            @JsonProperty("artifacts") List<Artifact> artifacts)
    {
        requireNonNull(artifacts, "artifacts are null");
        this.totalCount = totalCount;
        this.artifacts = artifacts;
    }

    public long getTotalCount()
    {
        return totalCount;
    }

    public List<Artifact> getItems()
    {
        return artifacts;
    }
}
