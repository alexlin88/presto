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
package io.prestosql.spiller;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.prestosql.block.BlockEncodingManager;
import io.prestosql.execution.buffer.PagesSerde;
import io.prestosql.execution.buffer.PagesSerdeFactory;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.operator.PageAssertions;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;
import io.prestosql.type.TypeRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.MoreFiles.listFiles;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static java.lang.Double.doubleToLongBits;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestFileSingleStreamSpiller
{
    private static final List<Type> TYPES = ImmutableList.of(BIGINT, DOUBLE, VARBINARY);

    private final ListeningExecutorService executor = listeningDecorator(newCachedThreadPool());
    private final File spillPath = Files.createTempDir();

    @AfterMethod(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        executor.shutdown();
        deleteRecursively(spillPath.toPath(), ALLOW_INSECURE);
    }

    @Test
    public void testSpill()
            throws Exception
    {
        PagesSerdeFactory serdeFactory = new PagesSerdeFactory(new BlockEncodingManager(new TypeRegistry()), false);
        PagesSerde serde = serdeFactory.createPagesSerde();
        SpillerStats spillerStats = new SpillerStats();
        LocalMemoryContext memoryContext = newSimpleAggregatedMemoryContext().newLocalMemoryContext("test");
        FileSingleStreamSpiller spiller = new FileSingleStreamSpiller(serde, executor, spillPath.toPath(), spillerStats, bytes -> {}, memoryContext);

        Page page = buildPage();

        // The spillers will reserve memory in their constructors
        assertEquals(memoryContext.getBytes(), 4096);
        spiller.spill(page).get();
        spiller.spill(Iterators.forArray(page, page, page)).get();
        assertEquals(listFiles(spillPath.toPath()).size(), 1);

        // The spillers release their memory reservations when they are closed, therefore at this point
        // they will have non-zero memory reservation.
        // assertEquals(memoryContext.getBytes(), 0);

        Iterator<Page> spilledPagesIterator = spiller.getSpilledPages();
        assertEquals(memoryContext.getBytes(), FileSingleStreamSpiller.BUFFER_SIZE);
        ImmutableList<Page> spilledPages = ImmutableList.copyOf(spilledPagesIterator);
        // The spillers release their memory reservations when they are closed, therefore at this point
        // they will have non-zero memory reservation.
        // assertEquals(memoryContext.getBytes(), 0);

        assertEquals(4, spilledPages.size());
        for (int i = 0; i < 4; ++i) {
            PageAssertions.assertPageEquals(TYPES, page, spilledPages.get(i));
        }

        spiller.close();
        assertEquals(listFiles(spillPath.toPath()).size(), 0);
        assertEquals(memoryContext.getBytes(), 0);
    }

    private Page buildPage()
    {
        BlockBuilder col1 = BIGINT.createBlockBuilder(null, 1);
        BlockBuilder col2 = DOUBLE.createBlockBuilder(null, 1);
        BlockBuilder col3 = VARBINARY.createBlockBuilder(null, 1);

        col1.writeLong(42).closeEntry();
        col2.writeLong(doubleToLongBits(43.0)).closeEntry();
        col3.writeLong(doubleToLongBits(43.0)).writeLong(1).closeEntry();

        return new Page(col1.build(), col2.build(), col3.build());
    }
}
